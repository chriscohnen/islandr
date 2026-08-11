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

function curvePath(x1, y1, x2, y2, bow = 0.18) {
  const dx = x2 - x1, dy = y2 - y1;
  const dist = Math.hypot(dx, dy) || 1;
  const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
  const cx = mx + (-dy / dist) * dist * bow;
  const cy = my + (dx / dist) * dist * bow;
  return `M ${x1} ${y1} Q ${cx} ${cy} ${x2} ${y2}`;
}

// Vogel's sunflower model: distributes N points evenly across a disk's whole
// area (not just its rim), so a circle with many nodes actually uses its
// interior instead of leaving it empty.
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

function sunflowerPoint(cx, cy, index, total, containerRadius) {
  const r = containerRadius * Math.sqrt((index + 0.5) / total);
  const theta = index * GOLDEN_ANGLE;
  return { x: cx + r * Math.cos(theta), y: cy + r * Math.sin(theta) };
}

// One circle's radius grows with its node count so packed nodes don't
// overlap — area-based (not circumference-based), since sunflower packing
// fills the interior. Adjacent points in the arrangement are ~1.2*R/sqrt(N)
// apart (empirical constant for the golden-angle spiral); solve for R that
// keeps that spacing above one node diameter plus a gap.
function circleRadiusFor(nodeCount) {
  if (nodeCount <= 1) return MIN_CIRCLE_RADIUS;
  const minSpacing = NODE_RADIUS * 2 + 10;
  const packedRadius = (minSpacing * Math.sqrt(nodeCount)) / 1.2;
  return Math.max(MIN_CIRCLE_RADIUS, packedRadius + NODE_RING_MARGIN);
}

// Shelf packing: circles are laid out in rows (like a masonry/tag-cloud
// layout), each row filled left-to-right up to targetWidth, wrapping to a
// new row once the next circle would exceed it. Rows are then centered and
// stacked. Unlike a tangent-circle blob, this fills a rectangular area
// predictably even when radii vary hugely (one big site circle next to many
// small ones) — the big circle just gets its own row instead of forcing an
// irregular, corner-wasting cluster shape.
function packCirclesIntoRows(items, targetAspect) {
  if (items.length === 0) return items;
  const totalArea = items.reduce((sum, i) => sum + Math.PI * i.r * i.r, 0);
  const targetWidth = Math.max(items[0].r * 2, Math.sqrt(totalArea * targetAspect) * 1.25);

  const rows = [];
  let row = [], x = 0;
  for (const item of items) {
    const d = item.r * 2;
    const nextX = row.length === 0 ? d : x + CIRCLE_GAP + d;
    if (row.length > 0 && nextX > targetWidth) {
      rows.push(row);
      row = [];
      x = 0;
    }
    item.localX = (row.length === 0 ? 0 : x + CIRCLE_GAP) + item.r;
    x = row.length === 0 ? d : x + CIRCLE_GAP + d;
    row.push(item);
  }
  rows.push(row);

  const rowWidths = rows.map((r) => Math.max(...r.map((i) => i.localX + i.r)));
  const maxRowWidth = Math.max(...rowWidths);

  let y = 0;
  rows.forEach((r, ri) => {
    const rowHeight = Math.max(...r.map((i) => i.r * 2));
    const offset = (maxRowWidth - rowWidths[ri]) / 2;
    for (const item of r) {
      item.x = item.localX + offset;
      item.y = y + rowHeight / 2;
    }
    y += rowHeight + CIRCLE_GAP;
  });
  return items;
}

const MIN_SCALE = 0.15;
const MAX_SCALE = 4;
const BUTTON_ZOOM_STEP = 1.6; // per +/- click or keypress — deliberate steps, no continuous wheel-zoom
const FIT_PADDING = 0.9;
// Screen-pixel movement below this, between pointerdown and pointerup on a
// user node, counts as a click (select/mode-switch) rather than a drag
// (grant creation) — see onWindowPointerUp.
const CLICK_DRAG_THRESHOLD = 6;

export default defineComponent({
  name: "AtlasDiagram",
  props: {
    graph: { type: Object, required: true }, // { users, resources, edges, roles }
    tool: { type: String, default: "grant" }, // "grant" | "revoke"
    highlightedUserIds: { type: Array, default: () => [] }, // user ids for the active role filter
    selectedUserId: { type: String, default: null }, // focused user (direct-grant mode) — only their edges render
    selectedResourceId: { type: String, default: null }, // focused resource — only edges reaching it render
  },
  emits: ["drag-grant", "revoke-edge", "user-click", "resource-click"],
  data() {
    return {
      dragFromUserId: null,
      dragPointer: null, // { x, y } in content coords, while dragging
      dragStartClient: null, // { x, y } in raw client px, to distinguish a click from a drag
      dragMoved: false,
      view: { scale: 1, tx: 0, ty: 0 },
      panning: false,
      panStart: null,
      hoveredNode: null, // resource node under the pointer, for the hover tooltip
      hoverPos: { x: 0, y: 0 }, // tooltip position, in container px
    };
  },
  computed: {
    highlightedUserIdSet() {
      return new Set(this.highlightedUserIds);
    },
    // One entry per site + one synthetic "mobile" entry for every User.
    circles() {
      const bySite = new Map();
      for (const r of this.graph.resources) {
        if (!bySite.has(r.siteId)) bySite.set(r.siteId, { id: r.siteId, name: r.siteName, cidr: r.siteCidr, resources: [] });
        bySite.get(r.siteId).resources.push(r);
      }
      const out = [];
      if (this.graph.users.length > 0) {
        out.push({ id: "__mobile__", name: t("atlas.circle_mobile"), kind: "mobile", nodes: this.graph.users });
      }
      for (const site of bySite.values()) {
        out.push({ id: site.id, name: site.name, cidr: site.cidr, kind: "site", nodes: site.resources });
      }
      return out;
    },
    layout() {
      const circles = this.circles;
      const mobileEntry = circles.find((c) => c.kind === "mobile");
      const siteEntries = circles.filter((c) => c.kind !== "mobile");

      // Site circles are packed into rows (shelf packing) instead of lined
      // up in a single strip, so busy layouts use the canvas area. Biasing
      // the target width wider than the canvas compensates for the mobile
      // row added below, which otherwise leaves the combined figure taller
      // than the wide canvas wants.
      const siteItems = siteEntries.map((c) => ({ ref: c, r: circleRadiusFor(c.nodes.length) }));
      siteItems.sort((a, b) => b.r - a.r);
      packCirclesIntoRows(siteItems, (W / H) * 1.3);

      let mobileItem = null;
      if (mobileEntry) {
        const mobileR = circleRadiusFor(mobileEntry.nodes.length);
        if (siteItems.length > 0) {
          // Mobile/roaming sits centered below the packed site blob, since
          // it represents people rather than a physical network — visually
          // distinct from the site circles clustered above it.
          const minX = Math.min(...siteItems.map((c) => c.x - c.r));
          const maxX = Math.max(...siteItems.map((c) => c.x + c.r));
          const maxY = Math.max(...siteItems.map((c) => c.y + c.r));
          mobileItem = { ref: mobileEntry, r: mobileR, x: (minX + maxX) / 2, y: maxY + CIRCLE_GAP + mobileR };
        } else {
          mobileItem = { ref: mobileEntry, r: mobileR, x: 0, y: 0 };
        }
      }

      const allItems = mobileItem ? [...siteItems, mobileItem] : siteItems;

      // The greedy packer clusters circles into a roughly round blob, but
      // the canvas is wide (viewBox is ~1.8:1) — fitToContent scales
      // uniformly to the tighter dimension, so a round blob leaves large
      // empty margins left/right. Stretch positions (not radii) horizontally
      // around the blob's center so the packed shape's aspect ratio
      // approaches the canvas's, without introducing overlaps.
      if (allItems.length > 1) {
        const minX = Math.min(...allItems.map((c) => c.x - c.r));
        const maxX = Math.max(...allItems.map((c) => c.x + c.r));
        const minY = Math.min(...allItems.map((c) => c.y - c.r));
        const maxY = Math.max(...allItems.map((c) => c.y + c.r));
        const blobW = maxX - minX, blobH = maxY - minY;
        const targetAspect = W / H;
        if (blobH > 0 && blobW / blobH < targetAspect) {
          const stretch = Math.min(2.2, targetAspect / (blobW / blobH));
          const cx0 = (minX + maxX) / 2;
          for (const item of allItems) item.x = cx0 + (item.x - cx0) * stretch;
        }
      }

      return allItems.map((item) => {
        const c = item.ref;
        const color = COOL_PALETTE[circles.indexOf(c) % COOL_PALETTE.length];
        const nodes = c.nodes.map((n, ni) => {
          const pos = c.nodes.length <= 1
              ? { x: item.x, y: item.y }
              : sunflowerPoint(item.x, item.y, ni, c.nodes.length, item.r - NODE_RING_MARGIN);
          return { ...n, x: pos.x, y: pos.y, circleId: c.id, isUser: c.kind === "mobile" };
        });
        return { ...c, cx: item.x, cy: item.y, r: item.r, color, nodes };
      });
    },
    nodesById() {
      const m = new Map();
      for (const circle of this.layout) for (const n of circle.nodes) m.set(n.id, n);
      return m;
    },
    edgeLines() {
      // A focused user or resource (click-select) shows only edges touching
      // that one node — every other edge is hidden entirely, not just
      // dimmed, so the graph reads as "what can this reach / be reached by"
      // without noise. The two focuses are mutually exclusive (see
      // AtlasView's click handlers).
      let source = this.graph.edges;
      if (this.selectedUserId) source = source.filter((e) => e.userId === this.selectedUserId);
      else if (this.selectedResourceId) source = source.filter((e) => e.resourceId === this.selectedResourceId);
      return source
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
    canZoomIn() { return this.view.scale < MAX_SCALE - 1e-6; },
    canZoomOut() { return this.view.scale > MIN_SCALE + 1e-6; },
  },
  watch: {
    graph() {
      this.$nextTick(() => this.fitToContent());
    },
  },
  mounted() {
    this.fitToContent();
    window.addEventListener("keydown", this.onKeyDown);
  },
  beforeUnmount() {
    window.removeEventListener("keydown", this.onKeyDown);
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
    // Wheel-zoom was removed (2026-08-11 feedback: too fast/twitchy on a
    // trackpad) in favor of the +/- buttons and keyboard shortcuts below,
    // both anchored on the viewBox center rather than the cursor.
    zoomAt(cursorX, cursorY, factor) {
      const contentX = (cursorX - this.view.tx) / this.view.scale;
      const contentY = (cursorY - this.view.ty) / this.view.scale;
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, this.view.scale * factor));
      this.view = { scale, tx: cursorX - contentX * scale, ty: cursorY - contentY * scale };
    },
    onKeyDown(evt) {
      const tag = (evt.target && evt.target.tagName) || "";
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || (evt.target && evt.target.isContentEditable)) return;
      if (evt.key === "+" || evt.key === "=") { evt.preventDefault(); this.zoomInBtn(); }
      else if (evt.key === "-" || evt.key === "_") { evt.preventDefault(); this.zoomOutBtn(); }
      else if (evt.key === "0") { evt.preventDefault(); this.fitToContent(); }
    },
    zoomInBtn() { this.zoomAt(W / 2, H / 2, BUTTON_ZOOM_STEP); },
    zoomOutBtn() { this.zoomAt(W / 2, H / 2, 1 / BUTTON_ZOOM_STEP); },
    onNodePointerDown(node, evt) {
      // Every user node tracks pointer movement, regardless of tool — a
      // click (no movement) always means select/mode-switch (emitted on
      // pointerup below); only a real drag additionally requires
      // tool==="grant" to attempt a grant.
      if (!node.isUser) return;
      evt.preventDefault();
      this.dragFromUserId = node.id;
      this.dragStartClient = { x: evt.clientX, y: evt.clientY };
      this.dragMoved = false;
      this.dragPointer = this.screenToContent(evt);
      window.addEventListener("pointermove", this.onWindowPointerMove);
      window.addEventListener("pointerup", this.onWindowPointerUp, { once: true });
      window.addEventListener("pointercancel", this.onWindowPointerCancel, { once: true });
    },
    onWindowPointerMove(evt) {
      if (!this.dragMoved && this.dragStartClient) {
        const dx = evt.clientX - this.dragStartClient.x;
        const dy = evt.clientY - this.dragStartClient.y;
        if (Math.hypot(dx, dy) > CLICK_DRAG_THRESHOLD) this.dragMoved = true;
      }
      this.dragPointer = this.screenToContent(evt);
    },
    endDrag() {
      window.removeEventListener("pointermove", this.onWindowPointerMove);
      window.removeEventListener("pointerup", this.onWindowPointerUp);
      window.removeEventListener("pointercancel", this.onWindowPointerCancel);
      this.dragFromUserId = null;
      this.dragPointer = null;
      this.dragStartClient = null;
      this.dragMoved = false;
    },
    onWindowPointerUp(evt) {
      const fromUserId = this.dragFromUserId;
      const moved = this.dragMoved;
      let resourceId = null;
      if (moved && this.tool === "grant") {
        const target = document.elementFromPoint(evt.clientX, evt.clientY);
        resourceId = target && target.closest("[data-resource-id]")
            ? target.closest("[data-resource-id]").getAttribute("data-resource-id")
            : null;
      }
      this.endDrag();
      if (moved) {
        if (resourceId && fromUserId) {
          this.$emit("drag-grant", { userId: fromUserId, resourceId });
        }
      } else if (fromUserId) {
        this.$emit("user-click", fromUserId);
      }
    },
    onWindowPointerCancel() {
      this.endDrag();
    },
    onEdgeClick(edge) {
      if (this.tool !== "revoke") return;
      this.$emit("revoke-edge", edge);
    },
    // Dim/highlight precedence: a focused user (click-select) always wins
    // over the role-membership highlight, since focusing narrows attention
    // to one specific person regardless of which role is active. Resource
    // focus follows the same idea on the resource side — user and resource
    // focus are mutually exclusive (AtlasView clears one when the other is set).
    nodeDimmed(node) {
      if (node.isUser) {
        if (this.selectedUserId) return node.id !== this.selectedUserId;
        if (this.highlightedUserIds.length > 0) return !this.highlightedUserIdSet.has(node.id);
        return false;
      }
      return this.selectedResourceId ? node.id !== this.selectedResourceId : false;
    },
    nodeHighlighted(node) {
      if (node.isUser) {
        if (this.selectedUserId) return node.id === this.selectedUserId;
        return this.highlightedUserIdSet.has(node.id);
      }
      return node.id === this.selectedResourceId;
    },
    onResourceNodeClick(node) {
      if (node.isUser) return;
      this.$emit("resource-click", node.id);
    },
    onResourceHover(node, evt) {
      this.hoveredNode = node;
      this.updateHoverPos(evt);
    },
    updateHoverPos(evt) {
      const rect = this.$refs.container.getBoundingClientRect();
      this.hoverPos = { x: evt.clientX - rect.left, y: evt.clientY - rect.top };
    },
    onResourceLeave() {
      this.hoveredNode = null;
    },
  },
  template: `
    <div ref="container" style="position: relative">
      <div class="muted" style="font-size: var(--text-xs); margin-bottom: var(--space-2)">{{ t('atlas.pan_zoom_hint') }}</div>
      <svg ref="svg" :viewBox="'0 0 ' + ${W} + ' ' + ${H}"
           style="width: 100%; height: auto; max-height: 70vh; touch-action: none"
           :style="panning ? 'cursor: grabbing' : ''"
           @pointerleave="dragPointer = null"
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
            <text :x="circle.cx" :y="circle.cy - circle.r - (circle.cidr ? 24 : 10)" text-anchor="middle"
                  :fill="circle.color" font-size="13" font-weight="600"
                  style="text-transform: uppercase; letter-spacing: 0.06em">{{ circle.name }}</text>
            <text v-if="circle.cidr" :x="circle.cx" :y="circle.cy - circle.r - 8" text-anchor="middle"
                  fill="var(--fg2)" font-size="11" font-family="var(--font-mono)">{{ circle.cidr }}</text>
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
               :style="node.isUser ? 'cursor: grab' : 'cursor: pointer'"
               @pointerdown="onNodePointerDown(node, $event)"
               @click="onResourceNodeClick(node)"
               @pointerenter="!node.isUser && onResourceHover(node, $event)"
               @pointermove="!node.isUser && hoveredNode === node && updateHoverPos($event)"
               @pointerleave="!node.isUser && onResourceLeave()">
              <circle :cx="node.x" :cy="node.y" :r="${NODE_RADIUS}"
                      :fill="node.isUser ? 'var(--accent)' : circle.color"
                      :fill-opacity="nodeDimmed(node) ? 0.3 : 1"
                      :stroke="nodeHighlighted(node) ? 'var(--fg1)' : 'var(--surface)'"
                      :stroke-width="nodeHighlighted(node) ? 3 : 2" />
              <text :x="node.x" :y="node.y + ${NODE_RADIUS} + 14" text-anchor="middle"
                    fill="var(--fg2)" font-size="11">{{ node.name }}</text>
            </g>
          </g>
        </g>
      </svg>

      <div style="position: absolute; right: 10px; bottom: 10px; display: flex; flex-direction: column; gap: 2px">
        <button type="button" class="btn btn-ghost btn-sm" :disabled="!canZoomIn" @click="zoomInBtn"
                :title="t('atlas.zoom_in')" style="width: 28px; height: 28px; padding: 0; font-size: var(--text-md)">+</button>
        <button type="button" class="btn btn-ghost btn-sm" :disabled="!canZoomOut" @click="zoomOutBtn"
                :title="t('atlas.zoom_out')" style="width: 28px; height: 28px; padding: 0; font-size: var(--text-md)">−</button>
        <button type="button" class="btn btn-ghost btn-sm" @click="fitToContent"
                :title="t('atlas.zoom_reset')" style="width: 28px; height: 28px; padding: 0; font-size: 10px">⟲</button>
      </div>

      <div v-if="hoveredNode" class="card card-pad" style="position: absolute; pointer-events: none; z-index: 10; min-width: 200px; box-shadow: var(--shadow-lg, 0 8px 24px rgba(0,0,0,0.18))"
           :style="{ left: (hoverPos.x + 16) + 'px', top: (hoverPos.y + 16) + 'px' }">
        <div style="font-weight: 600; margin-bottom: var(--space-2)">{{ hoveredNode.name }}</div>
        <div style="display: grid; grid-template-columns: auto auto; gap: 2px var(--space-3); font-size: var(--text-xs)">
          <span class="muted">{{ t('atlas.tooltip_site') }}</span><span>{{ hoveredNode.siteName }}</span>
          <template v-if="hoveredNode.siteCidr">
            <span class="muted">{{ t('atlas.tooltip_network') }}</span><span class="mono">{{ hoveredNode.siteCidr }}</span>
          </template>
          <template v-if="hoveredNode.ip">
            <span class="muted">{{ t('atlas.tooltip_ip') }}</span><span class="mono">{{ hoveredNode.ip }}</span>
          </template>
          <span class="muted">{{ t('atlas.tooltip_type') }}</span><span>{{ hoveredNode.type }}</span>
          <template v-if="hoveredNode.description">
            <span class="muted">{{ t('atlas.tooltip_description') }}</span><span>{{ hoveredNode.description }}</span>
          </template>
        </div>
      </div>
    </div>
  `,
});
