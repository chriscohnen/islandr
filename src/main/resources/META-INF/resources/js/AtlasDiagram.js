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

// Relaxation packing: seed every circle along an outward spiral (so nothing
// starts stacked on top of anything else), then repeatedly (a) nudge every
// circle a little toward the group's centroid, keeping the cluster compact
// instead of drifting apart, (b) nudge same-groupKey circles toward each
// other's own sub-centroid, harder than the global pull, so sites sharing a
// gateway peer — physically the same location — end up next to each other
// instead of scattered wherever the spiral seed happened to drop them, and
// (c) push any pair that still overlaps apart along the line between their
// centers. A few dozen passes converge on a tight, roughly circular cluster
// that adapts naturally to wildly different radii (one huge site next to
// several small ones) — nothing here assumes a fixed row/column structure
// the way a shelf-pack layout would.
function relaxCirclePositions(items, iterations = 200) {
  if (items.length === 0) return items;
  const spiralStep = 2.4; // radians per item — golden-angle-ish spread, not a full turn each step
  items.forEach((item, i) => {
    const t = i * spiralStep;
    const spiralR = 6 * Math.sqrt(i);
    item.x = spiralR * Math.cos(t);
    item.y = spiralR * Math.sin(t);
  });

  const groups = new Map();
  for (const item of items) {
    if (!item.groupKey) continue;
    if (!groups.has(item.groupKey)) groups.set(item.groupKey, []);
    groups.get(item.groupKey).push(item);
  }
  const coLocatedGroups = Array.from(groups.values()).filter((g) => g.length > 1);

  for (let iter = 0; iter < iterations; iter++) {
    const cx = items.reduce((sum, i) => sum + i.x, 0) / items.length;
    const cy = items.reduce((sum, i) => sum + i.y, 0) / items.length;
    for (const item of items) {
      item.x += (cx - item.x) * 0.015;
      item.y += (cy - item.y) * 0.015;
    }

    for (const group of coLocatedGroups) {
      const gx = group.reduce((sum, i) => sum + i.x, 0) / group.length;
      const gy = group.reduce((sum, i) => sum + i.y, 0) / group.length;
      for (const item of group) {
        item.x += (gx - item.x) * 0.06;
        item.y += (gy - item.y) * 0.06;
      }
    }

    let anyOverlap = false;
    for (let i = 0; i < items.length; i++) {
      for (let j = i + 1; j < items.length; j++) {
        const a = items[i], b = items[j];
        const dx = b.x - a.x, dy = b.y - a.y;
        const dist = Math.hypot(dx, dy) || 0.01;
        const minDist = a.r + b.r + CIRCLE_GAP;
        if (dist < minDist) {
          anyOverlap = true;
          const push = (minDist - dist) / 2;
          const ux = dx / dist, uy = dy / dist;
          a.x -= ux * push; a.y -= uy * push;
          b.x += ux * push; b.y += uy * push;
        }
      }
    }
    if (!anyOverlap && iter > 10) break; // converged — stop early on easy layouts
  }
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
    activeTypes: { type: Array, default: () => [] }, // non-empty = show only resources of these types
    activeUserIds: { type: Array, default: () => [] }, // non-empty = show only these users
  },
  emits: ["drag-grant", "revoke-edge", "user-click", "resource-click"],
  data() {
    return {
      dragFromUserId: null,
      dragFromIsGateway: false, // true when dragFromUserId is a site's gateway node, not a user
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
    // Inclusive filter (empty = show everything), same as the topology
    // map's type chips. Filtered-out nodes are dropped entirely, not just
    // dimmed — their edges disappear for free, since edgeLines below only
    // draws edges between nodes that made it into nodesById.
    visibleResources() {
      if (this.activeTypes.length === 0) return this.graph.resources;
      const active = new Set(this.activeTypes);
      return this.graph.resources.filter((r) => active.has(r.type));
    },
    visibleUsers() {
      if (this.activeUserIds.length === 0) return this.graph.users;
      const active = new Set(this.activeUserIds);
      return this.graph.users.filter((u) => active.has(u.id));
    },
    // One entry per site + one synthetic "mobile" entry for every User.
    circles() {
      const bySite = new Map();
      for (const r of this.visibleResources) {
        if (!bySite.has(r.siteId)) {
          bySite.set(r.siteId, {
            id: r.siteId, name: r.siteName, cidr: r.siteCidr,
            gatewayPeerId: r.siteGatewayPeerId, resources: [],
          });
        }
        bySite.get(r.siteId).resources.push(r);
      }
      // A site that only ever grants (never receives) access has no
      // ResourceNode of its own — without this, its gateway node would have
      // no circle to attach to and the grant it holds would be invisible.
      // Only add it if it actually holds a site-direct grant; otherwise an
      // active resource-type filter would fill the canvas with empty
      // circles for every site that merely has zero matching resources.
      const grantingSiteIds = new Set(
          this.graph.edges.filter((e) => e.subjectType === "site").map((e) => e.subjectId));
      for (const site of (this.graph.sites || [])) {
        if (!bySite.has(site.id) && grantingSiteIds.has(site.id)) {
          bySite.set(site.id, {
            id: site.id, name: site.name, cidr: site.cidr,
            gatewayPeerId: site.gatewayPeerId, resources: [],
          });
        }
      }
      const out = [];
      if (this.visibleUsers.length > 0) {
        out.push({ id: "__mobile__", name: t("atlas.circle_mobile"), kind: "mobile", nodes: this.visibleUsers });
      }
      for (const site of bySite.values()) {
        out.push({ id: site.id, name: site.name, cidr: site.cidr, gatewayPeerId: site.gatewayPeerId, kind: "site", nodes: site.resources });
      }
      return out;
    },
    layout() {
      const circles = this.circles;
      const mobileEntry = circles.find((c) => c.kind === "mobile");
      const siteEntries = circles.filter((c) => c.kind !== "mobile");

      // Site circles are packed via relaxation (spiral seed + iterative
      // overlap resolution) into a compact cluster, rather than lined up in
      // a strip or forced into rows. Sites sharing a gateway peer are the
      // same physical location, so they're tagged with a shared groupKey —
      // the relaxation pulls them toward each other extra hard.
      const siteItems = siteEntries.map((c) => ({
        ref: c, r: circleRadiusFor(c.nodes.length), groupKey: c.gatewayPeerId || null,
      }));
      siteItems.sort((a, b) => b.r - a.r);
      relaxCirclePositions(siteItems);

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
        // One gateway (grant-subject) node per site circle, anchored at the
        // top of the rim — outside the sunflower-packed radius
        // (item.r - NODE_RING_MARGIN) so it never collides with a resource
        // node, and below the external name/CIDR label so it never collides
        // with that either.
        if (c.kind === "site") {
          nodes.push({
            id: c.id, name: c.name, kind: "gateway",
            x: item.x, y: item.y - (item.r - NODE_RADIUS - 4),
            circleId: c.id, isUser: false, isGateway: true,
          });
        }
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
      // AtlasView's click handlers). Site focus/filtering is out of scope
      // for v1 (sites are grant subjects, not a filter mode), so only user
      // focus needs a subjectType check here.
      let source = this.graph.edges;
      if (this.selectedUserId) source = source.filter((e) => e.subjectType === "user" && e.subjectId === this.selectedUserId);
      else if (this.selectedResourceId) source = source.filter((e) => e.resourceId === this.selectedResourceId);
      return source
          .filter((e) => this.nodesById.has(e.subjectId) && this.nodesById.has(e.resourceId))
          .map((e) => {
            const from = this.nodesById.get(e.subjectId);
            const to = this.nodesById.get(e.resourceId);
            return { edge: e, path: curvePath(from.x, from.y, to.x, to.y), key: e.subjectType + "|" + e.subjectId + "|" + e.resourceId + "|" + e.kind + "|" + (e.roleId || "") };
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
      // Every user or gateway (site) node tracks pointer movement, regardless
      // of tool — a click (no movement) always means select/mode-switch
      // (emitted on pointerup below, user nodes only — see onWindowPointerUp);
      // only a real drag additionally requires tool==="grant" to attempt a grant.
      if (!node.isUser && !node.isGateway) return;
      evt.preventDefault();
      this.dragFromUserId = node.id;
      this.dragFromIsGateway = !!node.isGateway;
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
      this.dragFromIsGateway = false;
      this.dragPointer = null;
      this.dragStartClient = null;
      this.dragMoved = false;
    },
    onWindowPointerUp(evt) {
      const fromUserId = this.dragFromUserId;
      const fromIsGateway = this.dragFromIsGateway;
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
          this.$emit("drag-grant", { subjectType: fromIsGateway ? "site" : "user", subjectId: fromUserId, resourceId });
        }
      } else if (fromUserId && !fromIsGateway) {
        // A plain click on a gateway node is a no-op for now — sites are
        // grant subjects (draggable), not a filter/focus mode like users
        // (out of scope for v1).
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
    // Gateway (site) nodes have no filter/focus mode of their own (out of
    // scope for v1) — always shown at full opacity, never highlighted.
    nodeDimmed(node) {
      if (node.isGateway) return false;
      if (node.isUser) {
        if (this.selectedUserId) return node.id !== this.selectedUserId;
        if (this.highlightedUserIds.length > 0) return !this.highlightedUserIdSet.has(node.id);
        return false;
      }
      return this.selectedResourceId ? node.id !== this.selectedResourceId : false;
    },
    nodeHighlighted(node) {
      if (node.isGateway) return false;
      if (node.isUser) {
        if (this.selectedUserId) return node.id === this.selectedUserId;
        return this.highlightedUserIdSet.has(node.id);
      }
      return node.id === this.selectedResourceId;
    },
    // True only for the one individually click-selected node (user or
    // resource focus) — not the broader role-membership highlight, which can
    // mark many nodes at once and would turn the animated ring into noise.
    nodeFocused(node) {
      return node.id === this.selectedUserId || node.id === this.selectedResourceId;
    },
    onResourceNodeClick(node) {
      if (node.isUser || node.isGateway) return;
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
    diamondPath(cx, cy, r) {
      return `M ${cx} ${cy - r} L ${cx + r} ${cy} L ${cx} ${cy + r} L ${cx - r} ${cy} Z`;
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
               :data-resource-id="(!node.isUser && !node.isGateway) ? node.id : null"
               :style="(node.isUser || node.isGateway) ? 'cursor: grab' : 'cursor: pointer'"
               @pointerdown="onNodePointerDown(node, $event)"
               @click="onResourceNodeClick(node)"
               @pointerenter="!node.isUser && !node.isGateway && onResourceHover(node, $event)"
               @pointermove="!node.isUser && !node.isGateway && hoveredNode === node && updateHoverPos($event)"
               @pointerleave="!node.isUser && !node.isGateway && onResourceLeave()">
              <title v-if="node.isGateway">{{ t('atlas.tooltip_gateway', { site: node.name, cidr: circle.cidr }) }}</title>
              <circle v-if="nodeFocused(node)" :cx="node.x" :cy="node.y" :r="${NODE_RADIUS}"
                      fill="none" stroke="var(--fg1)" stroke-width="1.5">
                <animate attributeName="r" values="${NODE_RADIUS + 3};${NODE_RADIUS + 9};${NODE_RADIUS + 3}"
                         keyTimes="0;0.5;1" calcMode="spline" keySplines="0.42 0 0.58 1;0.42 0 0.58 1"
                         dur="2.2s" repeatCount="indefinite" />
                <animate attributeName="opacity" values="0.55;0.05;0.55"
                         keyTimes="0;0.5;1" calcMode="spline" keySplines="0.42 0 0.58 1;0.42 0 0.58 1"
                         dur="2.2s" repeatCount="indefinite" />
              </circle>
              <!-- A gateway (site) node is a diamond, never a circle — shape
                   alone must tell it apart from a resource or user node,
                   the same "never color-only" rule the rest of the system
                   applies to status. -->
              <path v-if="node.isGateway" :d="diamondPath(node.x, node.y, ${NODE_RADIUS})"
                    fill="var(--accent)"
                    :fill-opacity="nodeDimmed(node) ? 0.3 : 1"
                    :stroke="nodeHighlighted(node) ? 'var(--fg1)' : 'var(--surface)'"
                    :stroke-width="nodeHighlighted(node) ? 3 : 2" />
              <circle v-else :cx="node.x" :cy="node.y" :r="${NODE_RADIUS}"
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
