import { defineComponent } from "vue";
import { COOL_PALETTE } from "/js/Avatar.js";
import { t } from "/js/i18n.js";

// Layout constants (SVG viewBox units).
const W = 1000;
const H = 560;
const CIRCLE_GAP = 40;
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

// One circle's radius grows with its node count so nodes don't overlap.
function circleRadiusFor(nodeCount) {
  if (nodeCount <= 1) return MIN_CIRCLE_RADIUS;
  const ringCircumferenceNeeded = nodeCount * (NODE_RADIUS * 2 + 10);
  const ringRadius = ringCircumferenceNeeded / (2 * Math.PI);
  return Math.max(MIN_CIRCLE_RADIUS, ringRadius + NODE_RING_MARGIN);
}

const MIN_SCALE = 0.15;
const MAX_SCALE = 4;
const ZOOM_STEP = 1.1;
const FIT_PADDING = 0.9;

export default defineComponent({
  name: "AtlasDiagram",
  props: {
    graph: { type: Object, required: true }, // { users, resources, edges, roles }
    tool: { type: String, default: "grant" }, // "grant" | "revoke"
    highlightedUserIds: { type: Array, default: () => [] }, // user ids for the active role filter
  },
  emits: ["drag-grant", "revoke-edge"],
  data() {
    return {
      dragFromUserId: null,
      dragPointer: null, // { x, y } in content coords, while dragging
      view: { scale: 1, tx: 0, ty: 0 },
      panning: false,
      panStart: null,
    };
  },
  computed: {
    // One entry per site + one synthetic "mobile" entry for every User.
    circles() {
      const bySite = new Map();
      for (const r of this.graph.resources) {
        if (!bySite.has(r.siteId)) bySite.set(r.siteId, { id: r.siteId, name: r.siteName, resources: [] });
        bySite.get(r.siteId).resources.push(r);
      }
      const out = [];
      if (this.graph.users.length > 0) {
        out.push({ id: "__mobile__", name: t("atlas.circle_mobile"), kind: "mobile", nodes: this.graph.users });
      }
      for (const site of bySite.values()) {
        out.push({ id: site.id, name: site.name, kind: "site", nodes: site.resources });
      }
      return out;
    },
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
          return { ...n, x: pos.x, y: pos.y, circleId: c.id, isUser: c.kind === "mobile" };
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
          .filter((e) => this.nodesById.has(e.userId) && this.nodesById.has(e.resourceId))
          .map((e) => {
            const from = this.nodesById.get(e.userId);
            const to = this.nodesById.get(e.resourceId);
            return { edge: e, path: curvePath(from.x, from.y, to.x, to.y), key: e.userId + "|" + e.resourceId + "|" + e.kind + "|" + (e.roleId || "") };
          });
    },
    contentBounds() {
      if (this.layout.length === 0) return { minX: 0, minY: 0, maxX: W, maxY: H };
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const circle of this.layout) {
        minX = Math.min(minX, circle.cx - circle.r);
        maxX = Math.max(maxX, circle.cx + circle.r);
        minY = Math.min(minY, circle.cy - circle.r - 24);
        maxY = Math.max(maxY, circle.cy + circle.r);
      }
      return { minX, minY, maxX, maxY };
    },
    contentTransform() {
      return "translate(" + this.view.tx + "," + this.view.ty + ") scale(" + this.view.scale + ")";
    },
  },
  watch: {
    graph() {
      this.$nextTick(() => this.fitToContent());
    },
  },
  mounted() {
    this.fitToContent();
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    svgPoint(evt) {
      const svg = this.$refs.svg;
      const pt = svg.createSVGPoint();
      pt.x = evt.clientX;
      pt.y = evt.clientY;
      const ctm = svg.getScreenCTM();
      const local = pt.matrixTransform(ctm.inverse());
      return { x: local.x, y: local.y };
    },
    screenToContent(evt) {
      const p = this.svgPoint(evt);
      return { x: (p.x - this.view.tx) / this.view.scale, y: (p.y - this.view.ty) / this.view.scale };
    },
    clientDeltaToViewBox(dxClient, dyClient) {
      const rect = this.$refs.svg.getBoundingClientRect();
      return { dx: dxClient * (W / rect.width), dy: dyClient * (H / rect.height) };
    },
    fitToContent() {
      const b = this.contentBounds;
      const contentW = Math.max(1, b.maxX - b.minX);
      const contentH = Math.max(1, b.maxY - b.minY);
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE,
          Math.min(W / contentW, H / contentH) * FIT_PADDING));
      const cx = (b.minX + b.maxX) / 2;
      const cy = (b.minY + b.maxY) / 2;
      this.view = { scale, tx: W / 2 - cx * scale, ty: H / 2 - cy * scale };
    },
    onBackgroundPointerDown(evt) {
      this.panning = true;
      this.panStart = { clientX: evt.clientX, clientY: evt.clientY, tx: this.view.tx, ty: this.view.ty };
      window.addEventListener("pointermove", this.onBackgroundPointerMove);
      window.addEventListener("pointerup", this.onBackgroundPointerUp, { once: true });
    },
    onBackgroundPointerMove(evt) {
      if (!this.panning || !this.panStart) return;
      const { dx, dy } = this.clientDeltaToViewBox(
          evt.clientX - this.panStart.clientX, evt.clientY - this.panStart.clientY);
      this.view = { ...this.view, tx: this.panStart.tx + dx, ty: this.panStart.ty + dy };
    },
    onBackgroundPointerUp() {
      this.panning = false;
      this.panStart = null;
      window.removeEventListener("pointermove", this.onBackgroundPointerMove);
    },
    onWheelZoom(evt) {
      const rect = this.$refs.svg.getBoundingClientRect();
      const cursorX = (evt.clientX - rect.left) * (W / rect.width);
      const cursorY = (evt.clientY - rect.top) * (H / rect.height);
      const contentX = (cursorX - this.view.tx) / this.view.scale;
      const contentY = (cursorY - this.view.ty) / this.view.scale;
      const factor = evt.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, this.view.scale * factor));
      this.view = { scale, tx: cursorX - contentX * scale, ty: cursorY - contentY * scale };
    },
    onNodePointerDown(node, evt) {
      if (this.tool !== "grant" || !node.isUser) return;
      evt.preventDefault();
      this.dragFromUserId = node.id;
      this.dragPointer = this.screenToContent(evt);
      window.addEventListener("pointermove", this.onWindowPointerMove);
      window.addEventListener("pointerup", this.onWindowPointerUp, { once: true });
      window.addEventListener("pointercancel", this.onWindowPointerCancel, { once: true });
    },
    onWindowPointerMove(evt) {
      this.dragPointer = this.screenToContent(evt);
    },
    endDrag() {
      window.removeEventListener("pointermove", this.onWindowPointerMove);
      window.removeEventListener("pointerup", this.onWindowPointerUp);
      window.removeEventListener("pointercancel", this.onWindowPointerCancel);
      this.dragFromUserId = null;
      this.dragPointer = null;
    },
    onWindowPointerUp(evt) {
      const fromUserId = this.dragFromUserId;
      const target = document.elementFromPoint(evt.clientX, evt.clientY);
      const resourceId = target && target.closest("[data-resource-id]")
          ? target.closest("[data-resource-id]").getAttribute("data-resource-id")
          : null;
      this.endDrag();
      if (resourceId && fromUserId) {
        this.$emit("drag-grant", { userId: fromUserId, resourceId });
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
    <div>
      <div class="muted" style="font-size: var(--text-xs); margin-bottom: var(--space-2)">{{ t('atlas.pan_zoom_hint') }}</div>
      <svg ref="svg" :viewBox="'0 0 ' + ${W} + ' ' + ${H}"
           style="width: 100%; height: auto; max-height: 70vh; touch-action: none"
           :style="panning ? 'cursor: grabbing' : ''"
           @pointerleave="dragPointer = null"
           @wheel.prevent="onWheelZoom"
           @dblclick="fitToContent">
        <rect x="0" y="0" :width="${W}" :height="${H}" fill="transparent"
              :style="panning ? 'cursor: grabbing' : 'cursor: grab'"
              @pointerdown="onBackgroundPointerDown" />

        <defs>
          <marker id="atlas-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" fill="var(--accent)" />
          </marker>
        </defs>

        <g :transform="contentTransform">
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

          <line v-if="dragFromUserId && dragPointer" :x1="nodesById.get(dragFromUserId).x" :y1="nodesById.get(dragFromUserId).y"
                :x2="dragPointer.x" :y2="dragPointer.y"
                stroke="var(--accent)" stroke-width="2" stroke-dasharray="4 3" />

          <g v-for="circle in layout" :key="'nodes-' + circle.id">
            <g v-for="node in circle.nodes" :key="node.id"
               :data-resource-id="!node.isUser ? node.id : null"
               :style="node.isUser ? 'cursor: grab' : ''"
               @pointerdown="onNodePointerDown(node, $event)">
              <circle :cx="node.x" :cy="node.y" :r="${NODE_RADIUS}"
                      :fill="node.isUser ? 'var(--accent)' : circle.color"
                      :stroke="node.isUser && highlightedUserIds.includes(node.id) ? 'var(--fg1)' : 'var(--surface)'"
                      :stroke-width="node.isUser && highlightedUserIds.includes(node.id) ? 3 : 2" />
              <text :x="node.x" :y="node.y + ${NODE_RADIUS} + 14" text-anchor="middle"
                    fill="var(--fg2)" font-size="11">{{ node.name }}</text>
            </g>
          </g>
        </g>
      </svg>
    </div>
  `,
});
