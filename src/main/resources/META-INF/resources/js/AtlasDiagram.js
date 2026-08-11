import { defineComponent } from "vue";
import { COOL_PALETTE } from "/js/Avatar.js";
import { t } from "/js/i18n.js";

// Layout constants (SVG viewBox units).
const W = 1000;
const H = 560;
const CIRCLE_GAP = 40;
const CIRCLE_PADDING = 28;
const NODE_RADIUS = 16;
const NODE_RING_MARGIN = 26; // distance from circle edge to node centers
const MIN_CIRCLE_RADIUS = 90;

function polar(cx, cy, angle, dist) {
  return { x: cx + dist * Math.cos(angle), y: cy + dist * Math.sin(angle) };
}

function curvePath(x1, y1, x2, y2, bow = 0.18) {
  const dx = x2 - x1, dy = y2 - y1;
  const dist = Math.hypot(dx, dy) || 1;
  const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
  const cx = mx + (-dy / dist) * dist * bow;
  const cy = my + (dx / dist) * dist * bow;
  return `M ${x1} ${y1} Q ${cx} ${cy} ${x2} ${y2}`;
}

// One circle's radius grows with its node count so nodes don't overlap —
// same idea as TopologyDiagram.js's fan-angle spacing, but for a ring instead
// of a fan.
function circleRadiusFor(nodeCount) {
  if (nodeCount <= 1) return MIN_CIRCLE_RADIUS;
  const ringCircumferenceNeeded = nodeCount * (NODE_RADIUS * 2 + 10);
  const ringRadius = ringCircumferenceNeeded / (2 * Math.PI);
  return Math.max(MIN_CIRCLE_RADIUS, ringRadius + NODE_RING_MARGIN);
}

export default defineComponent({
  name: "AtlasDiagram",
  props: {
    graph: { type: Object, required: true }, // { peers, resources, edges, roles }
    tool: { type: String, default: "grant" }, // "grant" | "revoke"
  },
  emits: ["drag-grant", "revoke-edge"],
  data() {
    return {
      dragFromPeerId: null,
      dragPointer: null, // { x, y } in SVG coords, while dragging
    };
  },
  computed: {
    // One entry per site + one synthetic "mobile" entry for the user's peers.
    // Order: mobile circle first (it's always present when there are peers),
    // then sites in the order resources arrived (already site-name-sorted by
    // the backend query).
    circles() {
      const bySite = new Map();
      for (const r of this.graph.resources) {
        if (!bySite.has(r.siteId)) bySite.set(r.siteId, { id: r.siteId, name: r.siteName, resources: [] });
        bySite.get(r.siteId).resources.push(r);
      }
      const out = [];
      if (this.graph.peers.length > 0) {
        out.push({ id: "__mobile__", name: t("atlas.circle_mobile"), kind: "mobile", nodes: this.graph.peers });
      }
      for (const site of bySite.values()) {
        out.push({ id: site.id, name: site.name, kind: "site", nodes: site.resources });
      }
      return out;
    },
    // Layout: circles placed left-to-right, vertically centered, each sized
    // by its own node count.
    layout() {
      const circles = this.circles;
      const radii = circles.map((c) => circleRadiusFor(c.nodes.length));
      const totalWidth = radii.reduce((sum, r) => sum + r * 2, 0) + CIRCLE_GAP * Math.max(0, circles.length - 1);
      let x = (W - totalWidth) / 2;
      const cy = H / 2;
      const placed = [];
      circles.forEach((c, i) => {
        const r = radii[i];
        const cx = x + r;
        x += r * 2 + CIRCLE_GAP;
        const color = COOL_PALETTE[i % COOL_PALETTE.length];
        const nodes = c.nodes.map((n, ni) => {
          const angle = (2 * Math.PI * ni) / Math.max(1, c.nodes.length) - Math.PI / 2;
          const dist = c.nodes.length <= 1 ? 0 : r - NODE_RING_MARGIN;
          const pos = polar(cx, cy, angle, dist);
          return { ...n, x: pos.x, y: pos.y, circleId: c.id, isPeer: c.kind === "mobile" };
        });
        placed.push({ ...c, cx, cy, r, color, nodes });
      });
      return placed;
    },
    nodesById() {
      const m = new Map();
      for (const circle of this.layout) for (const n of circle.nodes) m.set(n.id, n);
      return m;
    },
    edgeLines() {
      return this.graph.edges
          .filter((e) => this.nodesById.has(e.peerId) && this.nodesById.has(e.resourceId))
          .map((e) => {
            const from = this.nodesById.get(e.peerId);
            const to = this.nodesById.get(e.resourceId);
            return { edge: e, path: curvePath(from.x, from.y, to.x, to.y), key: e.peerId + "|" + e.resourceId + "|" + e.roleId };
          });
    },
  },
  methods: {
    svgPoint(evt) {
      const svg = this.$refs.svg;
      const pt = svg.createSVGPoint();
      pt.x = evt.clientX;
      pt.y = evt.clientY;
      const ctm = svg.getScreenCTM();
      const local = pt.matrixTransform(ctm.inverse());
      return { x: local.x, y: local.y };
    },
    onNodePointerDown(node, evt) {
      if (this.tool !== "grant" || !node.isPeer) return;
      evt.preventDefault();
      this.dragFromPeerId = node.id;
      this.dragPointer = this.svgPoint(evt);
      window.addEventListener("pointermove", this.onWindowPointerMove);
      window.addEventListener("pointerup", this.onWindowPointerUp, { once: true });
      window.addEventListener("pointercancel", this.onWindowPointerCancel, { once: true });
    },
    onWindowPointerMove(evt) {
      this.dragPointer = this.svgPoint(evt);
    },
    endDrag() {
      window.removeEventListener("pointermove", this.onWindowPointerMove);
      window.removeEventListener("pointerup", this.onWindowPointerUp);
      window.removeEventListener("pointercancel", this.onWindowPointerCancel);
      this.dragFromPeerId = null;
      this.dragPointer = null;
    },
    onWindowPointerUp(evt) {
      const fromPeerId = this.dragFromPeerId;
      const target = document.elementFromPoint(evt.clientX, evt.clientY);
      const resourceId = target && target.closest("[data-resource-id]")
          ? target.closest("[data-resource-id]").getAttribute("data-resource-id")
          : null;
      this.endDrag();
      if (resourceId && fromPeerId) {
        this.$emit("drag-grant", { peerId: fromPeerId, resourceId });
      }
    },
    onWindowPointerCancel() {
      this.endDrag();
    },
    onEdgeClick(edge) {
      if (this.tool !== "revoke") return;
      this.$emit("revoke-edge", edge);
    },
  },
  template: `
    <svg ref="svg" :viewBox="'0 0 ' + ${W} + ' ' + ${H}" style="width: 100%; height: auto; max-height: 70vh" @pointerleave="dragPointer = null">
      <g v-for="circle in layout" :key="circle.id">
        <circle :cx="circle.cx" :cy="circle.cy" :r="circle.r"
                :fill="circle.color" fill-opacity="0.07"
                :stroke="circle.color" stroke-width="1.5" />
        <text :x="circle.cx" :y="circle.cy - circle.r - 10" text-anchor="middle"
              :fill="circle.color" font-size="13" font-weight="600"
              style="text-transform: uppercase; letter-spacing: 0.06em">{{ circle.name }}</text>
      </g>

      <path v-for="line in edgeLines" :key="line.key" :d="line.path"
            fill="none" stroke="var(--accent)" stroke-width="1.5"
            :stroke-opacity="tool === 'revoke' ? 0.85 : 0.55"
            :style="tool === 'revoke' ? 'cursor: pointer' : ''"
            marker-end="url(#atlas-arrow)"
            @click="onEdgeClick(line.edge)" />

      <line v-if="dragFromPeerId && dragPointer" :x1="nodesById.get(dragFromPeerId).x" :y1="nodesById.get(dragFromPeerId).y"
            :x2="dragPointer.x" :y2="dragPointer.y"
            stroke="var(--accent)" stroke-width="2" stroke-dasharray="4 3" />

      <defs>
        <marker id="atlas-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
          <path d="M0,0 L8,4 L0,8 Z" fill="var(--accent)" />
        </marker>
      </defs>

      <g v-for="circle in layout" :key="'nodes-' + circle.id">
        <g v-for="node in circle.nodes" :key="node.id"
           :data-resource-id="!node.isPeer ? node.id : null"
           :style="node.isPeer ? 'cursor: grab' : ''"
           @pointerdown="onNodePointerDown(node, $event)">
          <circle :cx="node.x" :cy="node.y" :r="${NODE_RADIUS}"
                  :fill="node.isPeer ? 'var(--accent)' : (node.reachable ? circle.color : 'var(--neutral-300)')"
                  :fill-opacity="node.isPeer || node.reachable ? 1 : 0.35"
                  stroke="var(--surface)" stroke-width="2" />
          <text :x="node.x" :y="node.y + ${NODE_RADIUS} + 14" text-anchor="middle"
                fill="var(--fg2)" font-size="11">{{ node.name }}</text>
        </g>
      </g>
    </svg>
  `,
});
