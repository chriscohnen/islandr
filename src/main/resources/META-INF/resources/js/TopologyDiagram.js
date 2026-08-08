import { defineComponent } from "vue";
import { PATHS as ICON_PATHS } from "/js/Icons.js";
import { t, relativeTime } from "/js/i18n.js";

// Three-tier radial topology with nested collapse/expand (issue #24):
//   Hub (circle) → gateway peers (router-shaped box) + direct networks (box)
//                → networks grouped under an expanded gateway (box)
//                → resources of an expanded network (circle, leaf)
// A gateway node groups every site that shares its gatewayPeerId — e.g. five
// networks routed through the same site router render as one hub spoke that
// fans out into five network boxes on click, instead of five independent
// spokes. Sites with no gateway stay direct hub spokes, rendered the same box
// shape so "network" always looks the same regardless of how it's reached.
// Type-filter chips narrow which resources count / appear.

const W = 720;
const H = 480;
const CX = W / 2;   // hub center X
const CY = H / 2;   // hub center Y
const HUB_R = 30;
const FIRST_RING = 150;          // hub → gateway peers + direct networks
const NETWORK_RING_OFFSET = 90;  // gateway → its grouped networks
const NODE_HALF_W = 24;          // gateway/network box half-width
const NODE_HALF_H = 14;          // gateway/network box half-height
const NODE_RX = 8;
// Resources of an expanded network render as a "netzplan"-style row list —
// a vertical stack of icon+name rows off a tree spine — instead of a
// circular fan. A fan of circular nodes reads fine for a handful of
// resources but turns into an unreadable starburst well before 20-30 (the
// exact case flagged in TODO.md's "Darstellung bei sehr vielen Ressourcen"),
// where a plain scrollable-feeling list stays legible at any count.
const RESOURCE_ROW_H = 22;       // vertical spacing between resource rows
const RESOURCE_ICON_R = 8;       // small icon circle at the start of each row
const RESOURCE_LIST_GAP = 50;    // network box edge → row icons, horizontal
const RESOURCE_SPINE_GAP = 22;   // network box edge → the vertical tree spine
const RESOURCE_LABEL_W = 130;    // approx label width, for viewBox sizing only
const LIVE_DOT_R = 4;
const LIVE_DOT_ORBIT = 78; // inside FIRST_RING, but far enough out for a name+IP label under the dot

// The viewBox fits tightly around whatever's on screen (see contentBBox) but
// is capped here so a big topology doesn't shrink nodes/labels into
// unreadable specks — past this size the viewBox stops growing and the user
// drags to pan across the (larger) content instead.
const MAX_VIEW_W = 900;
const MAX_VIEW_H = 620;
const PAN_SLACK = 40; // a little overscroll room at the content's own edges
const DRAG_THRESHOLD_PX = 4; // pointer movement before a press counts as a pan, not a click

const ALL_TYPES = [
  { key: "computer",   labelKey: "resources.type_computer" },
  { key: "nas",        labelKey: "resources.type_nas" },
  { key: "printer",    labelKey: "resources.type_printer" },
  { key: "router",     labelKey: "resources.type_router" },
  { key: "camera",     labelKey: "resources.type_camera" },
  { key: "iot",        labelKey: "resources.type_iot" },
  { key: "virt-host",  labelKey: "resources.type_virt" },
  { key: "management", labelKey: "resources.type_mgmt" },
  { key: "other",      labelKey: "resources.type_other" },
];

function angleAt(index, total, startDeg = -90) {
  const start = (startDeg * Math.PI) / 180;
  return start + (2 * Math.PI * index) / Math.max(total, 1);
}

/** Fan `count` children around `baseAngle`, same spread rule for every tier.
 * The span grows with `count` up to just short of a full circle — a fixed
 * 120° cap crushed busy sites (20+ resources) into an unreadable, heavily
 * overlapping stack instead of actually fanning out. */
function fanAngles(baseAngle, count) {
  if (count === 0) return [];
  if (count === 1) return [baseAngle];
  const MAX_SPAN = 2 * Math.PI * 0.92; // leave a gap back toward the parent link
  const arcSpan = Math.min(MAX_SPAN, (count - 1) * 0.35 + 0.3);
  const arcStart = baseAngle - arcSpan / 2;
  const step = arcSpan / (count - 1);
  return Array.from({ length: count }, (_, i) => arcStart + i * step);
}

function polar(angle, dist) {
  return { x: CX + dist * Math.cos(angle), y: CY + dist * Math.sin(angle) };
}

// Quadratic-bezier path between two points, bowed to one side — gives the
// hub's spokes a mindmap-style swoop instead of ruler-straight lines. The
// resource list below a network stays straight-line "netzplan" style on
// purpose (see RESOURCE_* comment); this is only for the radial hub/gateway/
// network spokes and the live-peer lines fanning off the hub.
const CURVE_BOW = 0.24;
function curvePath(x1, y1, x2, y2, bow = CURVE_BOW) {
  const dx = x2 - x1, dy = y2 - y1;
  const dist = Math.hypot(dx, dy) || 1;
  const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
  const cx = mx + (-dy / dist) * dist * bow;
  const cy = my + (dx / dist) * dist * bow;
  return `M ${x1} ${y1} Q ${cx} ${cy} ${x2} ${y2}`;
}

export default defineComponent({
  name: "TopologyDiagram",
  props: {
    sites:            { type: Array,  required: true },
    resources:        { type: Array,  required: true },
    livePeers:        { type: Array,  default: () => [] },
    resourceOverflow: { type: Number, default: 0 },
    endpoint:         { type: String, default: "" },
    hubLabel:         { type: String, default: "" },
    // Portal (self-service) reuse of this component (#43): gateway tunnel IP
    // and raw handshake timestamp are Admin-register technical detail the
    // backend already omits for portal callers — this only swaps the
    // *wording* of the connected/disconnected fallback text so "Handshake"
    // never appears in the end-user-facing tooltip.
    portal:           { type: Boolean, default: false },
  },
  emits: ["site", "resource"],
  data() {
    return {
      expandedGatewayId: null,
      expandedSiteId: null,
      activeTypes: new Set(),
      panX: 0,
      panY: 0,
      dragging: false,
      dragStartPointer: null,
      dragStartPan: null,
      _pendingPointerId: null,
      _pendingTarget: null,
      tooltip: null,         // { resource, x, y }
      networkTooltip: null,  // { site, x, y }
      gatewayTooltip: null,  // { gateway, x, y }
      // Uncapped resource lists fetched on demand when a drilled-into site's
      // resources didn't make the diagram-wide TOPOLOGY_RESOURCE_CAP in the
      // main payload. siteId -> TopologyResource[].
      siteResourceCache: {},
      siteResourceLoading: null,
    };
  },
  computed: {
    presentTypes() {
      const seen = new Set(this.resources.map((r) => r.type || "computer"));
      return ALL_TYPES.filter((ty) => seen.has(ty.key));
    },
    // Per-type counts for the filter chips (icon + count, Unifi Site
    // Manager-style) — same `resources` list/cap caveat as filteredResourceCount
    // below: undercounts a type once any of its resources fall outside the
    // diagram-wide TOPOLOGY_RESOURCE_CAP, fine for a filter-bar hint.
    typeCounts() {
      const counts = new Map();
      for (const r of this.resources) {
        const key = r.type || "computer";
        counts.set(key, (counts.get(key) || 0) + 1);
      }
      return counts;
    },
    filteredResources() {
      if (this.activeTypes.size === 0) return this.resources;
      return this.resources.filter((r) => this.activeTypes.has(r.type || "computer"));
    },
    // Counted from `resources`, which the backend caps at TOPOLOGY_RESOURCE_CAP
    // for the whole diagram (DashboardResource) — a network whose resources
    // didn't make the cap undercounts here even though it has real resources.
    // Only trustworthy as a per-site count once a type filter is active, where
    // there's no backend-supplied filtered count to fall back on. See
    // countForSite(), which prefers the accurate unfiltered backend count.
    filteredResourceCount() {
      const counts = new Map();
      for (const r of this.filteredResources) {
        counts.set(r.siteId, (counts.get(r.siteId) || 0) + 1);
      }
      return counts;
    },
    // Sites sharing a gatewayPeerId collapse into one hub spoke (a router-shaped
    // node) that fans into its member networks on click.
    gatewayGroups() {
      const map = new Map();
      for (const s of this.sites) {
        if (!s.gatewayPeerId) continue;
        if (!map.has(s.gatewayPeerId)) {
          map.set(s.gatewayPeerId, {
            gatewayPeerId: s.gatewayPeerId,
            gatewayPeerName: s.gatewayPeerName,
            gatewayOnline: s.gatewayOnline,
            gatewayIp: s.gatewayIp,
            gatewayLastSeenAt: s.gatewayLastSeenAt,
            sites: [],
          });
        }
        map.get(s.gatewayPeerId).sites.push(s);
      }
      return Array.from(map.values());
    },
    directSites() {
      return this.sites.filter((s) => !s.gatewayPeerId);
    },
    // First-ring hub spokes: one per gateway group, one per direct (ungated) network.
    gatewayLayout() {
      const total = this.gatewayGroups.length + this.directSites.length;
      return this.gatewayGroups.map((gw, i) => {
        const angle = angleAt(i, total);
        const { x, y } = polar(angle, FIRST_RING);
        return { gateway: gw, angle, x, y, expanded: gw.gatewayPeerId === this.expandedGatewayId };
      });
    },
    directNetworkLayout() {
      const total = this.gatewayGroups.length + this.directSites.length;
      return this.directSites.map((s, i) => {
        const angle = angleAt(this.gatewayGroups.length + i, total);
        const { x, y } = polar(angle, FIRST_RING);
        const count = this.countForSite(s);
        return { site: s, angle, x, y, dist: FIRST_RING, count, expanded: s.id === this.expandedSiteId };
      });
    },
    // Networks belonging to the currently expanded gateway, fanned around it.
    expandedGatewayNetworkLayout() {
      if (!this.expandedGatewayId) return [];
      const gwItem = this.gatewayLayout.find((g) => g.gateway.gatewayPeerId === this.expandedGatewayId);
      if (!gwItem) return [];
      const memberSites = gwItem.gateway.sites;
      const angles = fanAngles(gwItem.angle, memberSites.length);
      const dist = FIRST_RING + NETWORK_RING_OFFSET;
      return memberSites.map((s, i) => {
        const { x, y } = polar(angles[i], dist);
        const count = this.countForSite(s);
        return { site: s, angle: angles[i], x, y, dist, count, expanded: s.id === this.expandedSiteId,
                 parentX: gwItem.x, parentY: gwItem.y };
      });
    },
    // Every network box currently on screen — direct spokes plus whichever
    // gateway's group is expanded. Resource fan-out anchors into this list.
    visibleNetworks() {
      return [...this.directNetworkLayout, ...this.expandedGatewayNetworkLayout];
    },
    // One row per resource, stacked vertically off the expanded network's
    // box — see the RESOURCE_* comment above. Extends toward whichever side
    // of the hub the network box is already on (dir), so the list grows away
    // from the hub/other spokes instead of back over them.
    resourceLayout() {
      if (!this.expandedSiteId) return [];
      const net = this.visibleNetworks.find((n) => n.site.id === this.expandedSiteId);
      if (!net) return [];
      const cached = this.siteResourceCache[this.expandedSiteId];
      let list = cached
        ? (this.activeTypes.size === 0 ? cached : cached.filter((r) => this.activeTypes.has(r.type || "computer")))
        : this.filteredResources.filter((r) => r.siteId === this.expandedSiteId);
      if (list.length === 0) return [];
      const dir = net.x >= CX ? 1 : -1;
      const totalH = list.length * RESOURCE_ROW_H;
      const startY = net.y - totalH / 2 + RESOURCE_ROW_H / 2;
      const iconX = net.x + dir * (NODE_HALF_W + RESOURCE_LIST_GAP);
      return list.map((r, i) => ({
        resource: r, netX: net.x, netY: net.y, dir,
        x: iconX, y: startY + i * RESOURCE_ROW_H,
      }));
    },
    // x-position of the vertical tree spine the resource rows branch off of —
    // sits partway between the network box and the row icons.
    resourceSpineX() {
      if (this.resourceLayout.length === 0) return null;
      const first = this.resourceLayout[0];
      return first.netX + first.dir * (NODE_HALF_W + RESOURCE_SPINE_GAP);
    },
    // Short stub from the network box to the spine.
    resourceTrunk() {
      if (this.resourceLayout.length === 0) return null;
      const first = this.resourceLayout[0];
      return { x1: first.netX, y1: first.netY, x2: this.resourceSpineX, y2: first.netY };
    },
    // The spine itself, spanning every row's y — each row then branches off
    // it horizontally (rendered per-row in the template).
    resourceSpineLine() {
      if (this.resourceLayout.length === 0) return null;
      const ys = this.resourceLayout.map((r) => r.y);
      const x = this.resourceSpineX;
      return { x1: x, y1: Math.min(...ys), x2: x, y2: Math.max(...ys) };
    },
    livePeerLayout() {
      // Site-type peers are already represented by their gateway node (ring color
      // shows status) — exclude them here to avoid confusing duplicate dots.
      return this.livePeers.filter(p => p.type !== "site").slice(0, 8).map((p, i) => {
        const angle = angleAt(i, 8, -135);
        return { peer: p, x: CX + LIVE_DOT_ORBIT * Math.cos(angle), y: CY + LIVE_DOT_ORBIT * Math.sin(angle) };
      });
    },
    // Bounding box of everything actually on screen right now (hub, gateway
    // nodes, network boxes, and — if a network is drilled into — its resource
    // fan), padded, with a floor so a near-empty topology doesn't zoom in
    // absurdly. Replaces a fixed-size pan window that assumed a roughly
    // symmetric spread around the focused node: that assumption broke for
    // off-center sites and for networks with many resources, clipping nodes
    // at the top/edge while leaving the opposite side empty.
    contentBBox() {
      const pts = [
        { x: CX - HUB_R, y: CY - HUB_R },
        { x: CX + HUB_R, y: CY + HUB_R + 30 }, // hub label + endpoint line
      ];
      const addBox = (x, y, labelLines) => {
        pts.push({ x: x - NODE_HALF_W, y: y - NODE_HALF_H });
        pts.push({ x: x + NODE_HALF_W, y: y + NODE_HALF_H + 14 * labelLines });
      };
      const addResourceRow = (item) => {
        // The label hangs off the icon in the row's own direction (dir) —
        // pad that side by the approx label width, the icon side by just
        // the icon radius.
        const iconEdge = item.dir > 0 ? item.x - RESOURCE_ICON_R : item.x + RESOURCE_ICON_R;
        const labelEdge = item.dir > 0 ? item.x + RESOURCE_ICON_R + RESOURCE_LABEL_W
                                        : item.x - RESOURCE_ICON_R - RESOURCE_LABEL_W;
        pts.push({ x: Math.min(iconEdge, labelEdge), y: item.y - RESOURCE_ICON_R });
        pts.push({ x: Math.max(iconEdge, labelEdge), y: item.y + RESOURCE_ICON_R });
      };
      for (const item of this.gatewayLayout) addBox(item.x, item.y, item.gateway.sites.length > 1 ? 2 : 1);
      for (const item of this.visibleNetworks) addBox(item.x, item.y, item.expanded ? 2 : 1);
      for (const item of this.resourceLayout) addResourceRow(item);
      for (const d of this.livePeerLayout) {
        pts.push({ x: d.x - 40, y: d.y - LIVE_DOT_R });
        // Two label lines (name + IP) hang below the dot — pad enough room
        // for both, not just the dot itself, or the diagram clips them.
        pts.push({ x: d.x + 40, y: d.y + LIVE_DOT_R + 26 });
      }

      const PAD = 24;
      let minX = Math.min(...pts.map((p) => p.x)) - PAD;
      let maxX = Math.max(...pts.map((p) => p.x)) + PAD;
      let minY = Math.min(...pts.map((p) => p.y)) - PAD;
      let maxY = Math.max(...pts.map((p) => p.y)) + PAD;

      const MIN_W = 480, MIN_H = 320;
      let w = maxX - minX, h = maxY - minY;
      if (w < MIN_W) { const d = (MIN_W - w) / 2; minX -= d; maxX += d; w = MIN_W; }
      if (h < MIN_H) { const d = (MIN_H - h) / 2; minY -= d; maxY += d; h = MIN_H; }

      return { x: minX, y: minY, w, h };
    },
    // Content larger than the cap needs manual panning — everything past
    // MAX_VIEW_W/H no longer fits by shrinking, only by dragging.
    needsPan() {
      const b = this.contentBBox;
      return b.w > MAX_VIEW_W + 1 || b.h > MAX_VIEW_H + 1;
    },
    // Visible window: content bbox, capped at MAX_VIEW_W/H, recentered by
    // the current pan offset and clamped so dragging can't wander into empty
    // space far past the content's own edges (PAN_SLACK allows a little).
    viewBoxRect() {
      const b = this.contentBBox;
      const w = Math.min(b.w, MAX_VIEW_W);
      const h = Math.min(b.h, MAX_VIEW_H);
      const rangeX = (b.w - w) / 2 + PAN_SLACK;
      const rangeY = (b.h - h) / 2 + PAN_SLACK;
      const panX = Math.min(Math.max(this.panX, -rangeX), rangeX);
      const panY = Math.min(Math.max(this.panY, -rangeY), rangeY);
      const x = b.x + b.w / 2 - w / 2 + panX;
      const y = b.y + b.h / 2 - h / 2 + panY;
      return { x, y, w, h };
    },
    viewBox() {
      const b = this.viewBoxRect;
      return `${b.x} ${b.y} ${b.w} ${b.h}`;
    },
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    linkPath(x1, y1, x2, y2, bow) { return curvePath(x1, y1, x2, y2, bow); },
    // A resource row's ring is a ~160° arc, not a full circle: solid on the
    // side the branch line enters from (reads as the line flowing straight
    // into the arc), open on the side the label sits on (so icon + text sit
    // flush together instead of a ring boundary between them).
    resourceRingPath(dir) {
      const r = RESOURCE_ICON_R;
      // Icon sits further from the hub than the spine it branches off, so
      // the line always enters from the spine side — opposite the label's
      // outward direction (dir).
      const center = dir > 0 ? 180 : 0; // degrees — the line-entry side
      const steps = 16;
      const startDeg = center - 80, endDeg = center + 80;
      let d = "";
      for (let i = 0; i <= steps; i++) {
        const deg = startDeg + ((endDeg - startDeg) * i) / steps;
        const rad = (deg * Math.PI) / 180;
        d += (i === 0 ? "M" : "L") + (r * Math.cos(rad)).toFixed(2) + "," + (r * Math.sin(rad)).toFixed(2);
      }
      return d;
    },
    // Drag-to-pan, mouse and touch alike via Pointer Events. Only does
    // anything once the content no longer fits the capped viewBox
    // (needsPan) — otherwise everything's already visible and there's
    // nothing to pan to.
    onPointerDown(e) {
      if (!this.needsPan) return;
      // Don't commit to a drag (and don't capture the pointer) yet — a plain
      // click on a node must survive. Per the Pointer Events spec, once an
      // element captures the pointer, the pointerup/click for that pointer
      // gets redirected to the capturing element instead of the node under
      // the cursor, which silently ate clicks on network/gateway/resource
      // nodes whenever the diagram was big enough to need panning. Capture
      // is deferred to onPointerMove, once real movement crosses a small
      // threshold — that's what actually distinguishes a pan from a click.
      this.dragStartPointer = { x: e.clientX, y: e.clientY };
      this.dragStartPan = { x: this.panX, y: this.panY };
      this._pendingPointerId = e.pointerId;
      this._pendingTarget = e.currentTarget;
    },
    onPointerMove(e) {
      if (!this.dragStartPointer) return;
      if (!this.dragging) {
        const moved = Math.hypot(e.clientX - this.dragStartPointer.x, e.clientY - this.dragStartPointer.y);
        if (moved < DRAG_THRESHOLD_PX) return;
        this.dragging = true;
        this._pendingTarget.setPointerCapture(this._pendingPointerId);
      }
      const rect = e.currentTarget.getBoundingClientRect();
      const b = this.viewBoxRect;
      const scale = Math.min(rect.width / b.w, rect.height / b.h);
      if (!scale) return;
      // Content follows the cursor: dragging right reveals what was to the
      // left, so the viewBox itself moves the opposite way.
      const dxUser = (e.clientX - this.dragStartPointer.x) / scale;
      const dyUser = (e.clientY - this.dragStartPointer.y) / scale;
      this.panX = this.dragStartPan.x - dxUser;
      this.panY = this.dragStartPan.y - dyUser;
    },
    onPointerUp(e) {
      if (this.dragging) {
        try { e.currentTarget.releasePointerCapture(e.pointerId); } catch { /* already released */ }
      }
      this.dragging = false;
      this.dragStartPointer = null;
      this.dragStartPan = null;
      this._pendingPointerId = null;
      this._pendingTarget = null;
    },
    resetPan() { this.panX = 0; this.panY = 0; },
    // No active type filter → the backend's per-site count (site.resourceCount)
    // is the true, uncapped total (a plain GROUP BY over every resource) and is
    // what the Networks table shows too. A type filter narrows what should be
    // counted, and there's no backend-supplied filtered-per-site count to use
    // instead, so that case falls back to counting the (capped) resources array.
    countForSite(site) {
      if (this.activeTypes.size === 0) return site.resourceCount || 0;
      return this.filteredResourceCount.get(site.id) || 0;
    },
    networkIconMarkup() {
      return (ICON_PATHS.networks || []).join("");
    },
    routerIconMarkup() {
      return (ICON_PATHS.router || []).join("");
    },
    resourceIconMarkup(type) {
      const paths = ICON_PATHS[type || "computer"] || ICON_PATHS.computer;
      return paths.join("");
    },
    onGatewayClick(gatewayPeerId) {
      // Switching gateways (or collapsing one) always invalidates whichever
      // network was expanded — its box may no longer be on screen. The
      // viewBox itself re-fits reactively (contentBBox); only the manual
      // pan offset needs clearing so a drag from the old view doesn't leak
      // into the new one.
      this.expandedSiteId = null;
      this.resetPan();
      this.expandedGatewayId = this.expandedGatewayId === gatewayPeerId ? null : gatewayPeerId;
    },
    onNetworkClick(site) {
      this.resetPan();
      if (this.expandedSiteId === site.id) {
        this.expandedSiteId = null;
        return;
      }
      this.expandedSiteId = site.id;
      this.maybeLoadSiteResources(site);
    },
    // The diagram-wide payload caps resources at TOPOLOGY_RESOURCE_CAP; a
    // network whose resources didn't make that cap would otherwise fan out
    // into nothing when drilled into. Fetch its real, uncapped list once.
    async maybeLoadSiteResources(site) {
      if (this.siteResourceCache[site.id]) return;
      const cappedCount = this.resources.filter((r) => r.siteId === site.id).length;
      if (cappedCount >= site.resourceCount) return;
      this.siteResourceLoading = site.id;
      try {
        const res = await fetch(`/api/v1/dashboard/topology/site-resources/${site.id}`);
        if (res.ok) {
          const data = await res.json();
          this.siteResourceCache = { ...this.siteResourceCache, [site.id]: data };
        }
      } catch (e) {
        // Silent — the "hidden due to display limit" fallback in the template
        // still covers this site if the fetch fails.
      } finally {
        if (this.siteResourceLoading === site.id) this.siteResourceLoading = null;
      }
    },
    onResourceClick(siteId, resourceId) {
      this.$emit("resource", { siteId, resourceId });
    },
    toggleType(key) {
      const next = new Set(this.activeTypes);
      if (next.has(key)) next.delete(key); else next.add(key);
      this.activeTypes = next;
    },
    clearTypes() { this.activeTypes = new Set(); },
    showTooltip(event, resource) {
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { resource, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    moveTooltip(event) {
      if (!this.tooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { ...this.tooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hideTooltip() { this.tooltip = null; },
    showNetworkTooltip(event, site) {
      const rect = this.$el.getBoundingClientRect();
      this.networkTooltip = { site, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    moveNetworkTooltip(event) {
      if (!this.networkTooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.networkTooltip = { ...this.networkTooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hideNetworkTooltip() { this.networkTooltip = null; },
    showGatewayTooltip(event, gateway) {
      const rect = this.$el.getBoundingClientRect();
      this.gatewayTooltip = { gateway, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    moveGatewayTooltip(event) {
      if (!this.gatewayTooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.gatewayTooltip = { ...this.gatewayTooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hideGatewayTooltip() { this.gatewayTooltip = null; },
    gatewayRingStyle(item) {
      if (item.expanded) return "stroke: var(--accent); stroke-width: 3";
      return item.gateway.gatewayOnline
        ? "stroke: var(--status-ok); stroke-width: 2.5"
        : "stroke: var(--fg3); stroke-width: 2";
    },
    networkRingStyle(item) {
      return item.expanded ? "stroke: var(--accent); stroke-width: 3" : "";
    },
    relativeTime(iso) { return relativeTime(iso); },
    resourceTitle(r) {
      const ports = r.portLabels?.length > 0 ? r.portLabels.join(", ") : t("topology.no_ports");
      return `${r.name} · ${r.ip} · ${ports}`;
    },
  },
  template: `
    <div style="position: relative">
      <!-- Type filter chips -->
      <div v-if="presentTypes.length > 1"
           style="display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-3); font-family: var(--font-sans)">
        <button @click="clearTypes"
          :class="['btn','btn-sm', activeTypes.size === 0 ? 'btn-secondary' : 'btn-ghost']"
          style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px">
          {{ t('topology.filter_all') }}
          <span style="font-family: var(--font-mono); opacity: 0.6">{{ resources.length }}</span>
        </button>
        <button v-for="tp in presentTypes" :key="tp.key"
          @click="toggleType(tp.key)"
          :class="['btn','btn-sm', activeTypes.has(tp.key) ? 'btn-secondary' : 'btn-ghost']"
          style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
               style="flex-shrink: 0; opacity: 0.75" v-html="resourceIconMarkup(tp.key)" />
          {{ t(tp.labelKey) }}
          <span style="font-family: var(--font-mono); opacity: 0.6">{{ typeCounts.get(tp.key) || 0 }}</span>
        </button>
      </div>

      <div v-if="resourceOverflow > 0" class="muted"
           style="font-size: var(--text-xs); margin-bottom: var(--space-2)">
        {{ t('topology.resource_overflow', { count: resourceOverflow }) }}
      </div>

      <div v-if="sites.length === 0" class="topo-empty"
           style="position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none; z-index: 1">
        <span style="pointer-events: auto">{{ t('topology.empty_a') }}<router-link to="/networks" style="font-weight: 600; color: var(--fg1); text-decoration: underline">{{ t('nav.networks') }}</router-link>{{ t('topology.empty_b') }}</span>
      </div>

      <svg class="topo" :viewBox="viewBox"
           :style="{
             transition: dragging ? 'none' : 'viewBox 0.3s ease',
             cursor: needsPan ? (dragging ? 'grabbing' : 'grab') : 'default',
             touchAction: needsPan ? 'none' : 'auto',
             userSelect: 'none',
           }"
           @pointerdown="onPointerDown"
           @pointermove="onPointerMove"
           @pointerup="onPointerUp"
           @pointercancel="onPointerUp"
           role="img" aria-label="Netzwerk-Topologie">

        <!-- Hub-to-gateway / hub-to-direct-network links, swept into a gentle
             mindmap-style curve rather than a ruler-straight radius. -->
        <path v-for="item in gatewayLayout" :key="'gl-'+item.gateway.gatewayPeerId"
              :class="['link', item.gateway.gatewayOnline ? '' : 'link-down']"
              :d="linkPath(CX, CY, item.x, item.y)" />
        <path v-for="item in directNetworkLayout" :key="'dl-'+item.site.id"
              class="link" :d="linkPath(CX, CY, item.x, item.y)" />

        <!-- Gateway-to-network links (expanded gateway only) -->
        <path v-for="item in expandedGatewayNetworkLayout" :key="'gnl-'+item.site.id"
              class="link" style="opacity:0.45"
              :d="linkPath(item.parentX, item.parentY, item.x, item.y)" />

        <!-- Network-to-resource tree (expanded network only): a short trunk
             from the network box to a vertical spine, then one horizontal
             branch per resource row off that spine — see the RESOURCE_*
             comment near the top of the file for why this replaced the old
             circular fan. -->
        <line v-if="resourceTrunk" class="link" style="opacity:0.45"
              :x1="resourceTrunk.x1" :y1="resourceTrunk.y1" :x2="resourceTrunk.x2" :y2="resourceTrunk.y2" />
        <line v-if="resourceSpineLine" class="link" style="opacity:0.45"
              :x1="resourceSpineLine.x1" :y1="resourceSpineLine.y1" :x2="resourceSpineLine.x2" :y2="resourceSpineLine.y2" />
        <line v-for="item in resourceLayout" :key="'rl-'+item.resource.id"
              class="link" style="opacity:0.45"
              :x1="resourceSpineX" :y1="item.y" :x2="item.x" :y2="item.y" />

        <!-- Hub -->
        <circle class="hub-pulse" :cx="CX" :cy="CY" :r="HUB_R" />
        <circle class="hub-core"  :cx="CX" :cy="CY" :r="HUB_R - 6" />

        <!-- Brand mark (constellation), knocked out in --fg-on-accent so it
             reads on the accent-filled hub circle in both themes — same mark
             as favicon.svg/islandr-mark.svg, flattened and scaled to fit the
             hub-core radius. Purely decorative: no title/pointer-events, the
             hub-core circle underneath still carries any future interaction. -->
        <g class="hub-mark" :transform="'translate('+(CX-28.35)+','+(CY-28.35)+') scale(0.45)'" style="pointer-events:none">
          <path d="M27 102 L53 90 L71 64 L99 46 M71 64 L65 33" fill="none" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round" />
          <circle cx="27" cy="102" r="5" />
          <circle cx="53" cy="90" r="4.5" />
          <circle cx="71" cy="64" r="6" />
          <circle cx="99" cy="46" r="5" />
          <circle cx="65" cy="33" r="9" />
        </g>

        <text   class="hub-label" :x="CX" :y="CY + HUB_R + 16">{{ hubLabel || 'Hub' }}</text>
        <text v-if="endpoint" class="hub-endpoint" :x="CX" :y="CY + HUB_R + 30">{{ endpoint }}</text>

        <!-- Live-peer links + dots + labels. Unlike the static topology below,
             these come and go with recent handshake activity, so the link is
             thin/dashed and the dot small — but the name + IP are printed
             right on the diagram, not hidden behind a hover-only tooltip. -->
        <path v-for="d in livePeerLayout" :key="'ll-'+d.peer.id"
              :class="['link-live', d.peer.trafficTier === 'flowing' ? 'link-flowing' : '', d.peer.trafficTier === 'flowing-heavy' ? 'link-flowing-heavy' : '']"
              :d="linkPath(CX, CY, d.x, d.y, 0.1)" />
        <g v-for="d in livePeerLayout" :key="'lp-'+d.peer.id">
          <circle :cx="d.x" :cy="d.y" :r="LIVE_DOT_R" :class="['hub-core', d.peer.trafficTier !== 'idle' ? 'live-dot-flowing' : '']" style="opacity:0.85">
            <title>{{ d.peer.name || t('topology.unknown_peer') }} · {{ d.peer.assignedIp }} · {{ t('topology.traffic_' + (d.peer.trafficTier === 'flowing-heavy' ? 'flowing_heavy' : d.peer.trafficTier || 'idle')) }} · {{ relativeTime(d.peer.lastSeenAt) }}</title>
          </circle>
          <text class="live-label" :x="d.x" :y="d.y + LIVE_DOT_R + 12">{{ d.peer.name || t('topology.unknown_peer') }}</text>
          <text v-if="d.peer.assignedIp" class="live-ip" :x="d.x" :y="d.y + LIVE_DOT_R + 24">{{ d.peer.assignedIp }}</text>
        </g>

        <!-- Resource rows (expanded network only) — icon + name, "netzplan"
             list style. Hover still surfaces IP/ports via the same tooltip
             as before, just triggered off a row instead of a circle. -->
        <g v-for="item in resourceLayout" :key="item.resource.id"
           class="node resource"
           @click="onResourceClick(item.resource.siteId, item.resource.id)"
           @mouseenter="showTooltip($event, item.resource)"
           @mousemove="moveTooltip($event)"
           @mouseleave="hideTooltip"
           :transform="'translate('+item.x+','+item.y+')'">
          <circle class="node-bg" :r="RESOURCE_ICON_R - 2" />
          <path class="node-ring" fill="none" :d="resourceRingPath(item.dir)" />
          <g class="node-icon" transform="translate(-6,-6) scale(0.5)"
             fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round"
             v-html="resourceIconMarkup(item.resource.type)" />
          <text class="node-label"
                :x="item.dir > 0 ? RESOURCE_ICON_R + 6 : -(RESOURCE_ICON_R + 6)"
                y="4"
                :style="'text-anchor:' + (item.dir > 0 ? 'start' : 'end')">{{ item.resource.name }}</text>
        </g>

        <!-- Gateway-peer nodes — router silhouette (box, not circle): a shared
             site router groups every network routed through it into one spoke. -->
        <g v-for="item in gatewayLayout" :key="item.gateway.gatewayPeerId"
           :class="['node', item.gateway.gatewayOnline ? 'live' : 'disabled']"
           @click="onGatewayClick(item.gateway.gatewayPeerId)"
           @mouseenter="showGatewayTooltip($event, item.gateway)"
           @mousemove="moveGatewayTooltip($event)"
           @mouseleave="hideGatewayTooltip"
           :transform="'translate('+item.x+','+item.y+')'">
          <rect class="node-ring" :x="-NODE_HALF_W" :y="-NODE_HALF_H"
                :width="NODE_HALF_W*2" :height="NODE_HALF_H*2" :rx="NODE_RX"
                :style="gatewayRingStyle(item)" />
          <rect class="node-bg" :x="-NODE_HALF_W+2" :y="-NODE_HALF_H+2"
                :width="NODE_HALF_W*2-4" :height="NODE_HALF_H*2-4" :rx="NODE_RX-2" />
          <g class="node-icon" transform="translate(-9.6,-9.6) scale(0.8)"
             fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round"
             v-html="routerIconMarkup()" />
          <text class="node-label" :y="NODE_HALF_H + 15">{{ item.gateway.gatewayPeerName }}</text>
          <text v-if="item.gateway.sites.length > 1"
                style="font-family: var(--font-mono); font-size: 10px; font-weight: 700;
                       fill: var(--accent); text-anchor: middle; pointer-events: none"
                :y="NODE_HALF_H + 27">{{ item.gateway.sites.length }} {{ t('topology.networks_short') }}</text>
        </g>

        <!-- Network boxes — direct hub spokes, plus whichever gateway's group is expanded -->
        <g v-for="item in visibleNetworks" :key="item.site.id"
           class="node network"
           :style="!item.expanded && item.count === 0 ? 'opacity: 0.55' : ''"
           @click="onNetworkClick(item.site)"
           @mouseenter="showNetworkTooltip($event, item.site)"
           @mousemove="moveNetworkTooltip($event)"
           @mouseleave="hideNetworkTooltip"
           :transform="'translate('+item.x+','+item.y+')'">
          <rect class="node-ring" :x="-NODE_HALF_W" :y="-NODE_HALF_H"
                :width="NODE_HALF_W*2" :height="NODE_HALF_H*2" :rx="NODE_RX"
                :style="networkRingStyle(item)" />
          <rect class="node-bg" :x="-NODE_HALF_W+2" :y="-NODE_HALF_H+2"
                :width="NODE_HALF_W*2-4" :height="NODE_HALF_H*2-4" :rx="NODE_RX-2" />

          <g v-if="!item.expanded">
            <g class="node-icon" transform="translate(-6,-11) scale(0.45)"
               fill="none" stroke="currentColor" stroke-width="2.5"
               stroke-linecap="round" stroke-linejoin="round"
               v-html="networkIconMarkup()" />
            <text :style="'font-family: var(--font-mono); font-size: 12px; font-weight: 700; text-anchor: middle; dominant-baseline: central; user-select: none; fill: ' + (item.count === 0 ? 'var(--fg3)' : 'var(--accent)')"
                  y="6">{{ item.count }}</text>
          </g>
          <g v-else-if="item.site.id === expandedSiteId && siteResourceLoading !== item.site.id && resourceLayout.length === 0 && item.count > 0">
            <g class="node-icon" transform="translate(-6,-11) scale(0.45)"
               fill="none" stroke="currentColor" stroke-width="2.5"
               stroke-linecap="round" stroke-linejoin="round"
               v-html="networkIconMarkup()" />
            <text style="font-family: var(--font-mono); font-size: 12px; font-weight: 700;
                         fill: var(--fg3); text-anchor: middle; dominant-baseline: central;
                         user-select: none"
                  y="6">{{ item.count }}</text>
          </g>
          <g v-else class="node-icon"
             transform="translate(-8.8,-8.8) scale(0.73)"
             fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round"
             v-html="networkIconMarkup()" />

          <text class="node-label" :y="NODE_HALF_H + 15">{{ item.site.name }}</text>
          <text v-if="item.site.id === expandedSiteId && siteResourceLoading !== item.site.id && resourceLayout.length === 0 && item.count > 0"
                class="node-label" :y="NODE_HALF_H + 29"
                style="fill: var(--fg3); font-size: 10px">{{ t('topology.resources_hidden_cap') }}</text>
        </g>

        <!-- Hint -->
        <text v-if="!expandedGatewayId && !expandedSiteId" :x="viewBoxRect.x + viewBoxRect.w - 12" :y="viewBoxRect.y + viewBoxRect.h - 12"
              style="font-family:var(--font-sans);font-size:11px;fill:var(--fg3);text-anchor:end;pointer-events:none">
          {{ t('topology.expand_hint') }}
        </text>
      </svg>

      <!-- Gateway hover tooltip -->
      <div v-if="gatewayTooltip" :style="{
             position: 'absolute',
             left: gatewayTooltip.x + 'px',
             top: gatewayTooltip.y + 'px',
             pointerEvents: 'none',
             zIndex: 10,
             background: 'var(--surface-2)',
             border: '1px solid var(--border)',
             borderRadius: 'var(--radius-sm)',
             padding: '6px 10px',
             boxShadow: 'var(--shadow-md, 0 4px 12px rgba(0,0,0,0.3))',
             maxWidth: '220px',
           }">
        <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); margin-bottom: 4px">
          {{ gatewayTooltip.gateway.gatewayPeerName }}
        </div>
        <div style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
          <div v-if="!portal && gatewayTooltip.gateway.gatewayIp" style="margin-bottom: 2px">
            <span :style="gatewayTooltip.gateway.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                  style="font-size:9px">{{ gatewayTooltip.gateway.gatewayOnline ? '●' : '○' }}</span>
            <span style="font-family: var(--font-mono); color: var(--fg2)">{{ gatewayTooltip.gateway.gatewayIp }}</span>
          </div>
          <div v-if="portal">
            <span :style="gatewayTooltip.gateway.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                  style="font-size:9px">{{ gatewayTooltip.gateway.gatewayOnline ? '●' : '○' }}</span>
            {{ gatewayTooltip.gateway.gatewayOnline ? t('topology.portal_connected') : t('topology.portal_disconnected') }}
          </div>
          <div v-else>{{ gatewayTooltip.gateway.gatewayLastSeenAt ? t('topology.handshake', { when: relativeTime(gatewayTooltip.gateway.gatewayLastSeenAt) }) : t('topology.no_handshake') }}</div>
          <div style="margin-top: 2px">{{ gatewayTooltip.gateway.sites.length }} {{ t('topology.networks_short') }}</div>
        </div>
      </div>

      <!-- Network hover tooltip -->
      <div v-if="networkTooltip" :style="{
             position: 'absolute',
             left: networkTooltip.x + 'px',
             top: networkTooltip.y + 'px',
             pointerEvents: 'none',
             zIndex: 10,
             background: 'var(--surface-2)',
             border: '1px solid var(--border)',
             borderRadius: 'var(--radius-sm)',
             padding: '6px 10px',
             boxShadow: 'var(--shadow-md, 0 4px 12px rgba(0,0,0,0.3))',
             maxWidth: '220px',
           }">
        <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); margin-bottom: 4px">
          {{ networkTooltip.site.name }}
        </div>
        <div v-if="networkTooltip.site.cidr" style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg2)">
          {{ networkTooltip.site.cidr }}
        </div>
      </div>

      <!-- Resource hover tooltip -->
      <div v-if="tooltip" :style="{
             position: 'absolute',
             left: tooltip.x + 'px',
             top: tooltip.y + 'px',
             pointerEvents: 'none',
             zIndex: 10,
             background: 'var(--surface-2)',
             border: '1px solid var(--border)',
             borderRadius: 'var(--radius-sm)',
             padding: '6px 10px',
             boxShadow: 'var(--shadow-md, 0 4px 12px rgba(0,0,0,0.3))',
             maxWidth: '220px',
           }">
        <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); margin-bottom: 4px">
          {{ tooltip.resource.name }}
        </div>
        <div style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg2); margin-bottom: 2px">
          {{ tooltip.resource.ip }}
        </div>
        <div v-if="tooltip.resource.portLabels && tooltip.resource.portLabels.length > 0"
             style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg3); line-height: 1.5">
          <div v-for="p in tooltip.resource.portLabels" :key="p">{{ p }}</div>
        </div>
        <div v-else style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
          {{ t('topology.no_ports') }}
        </div>
      </div>
    </div>
  `,
  // Expose constants to template via data so Vue can see them.
  created() {
    this.CX = CX; this.CY = CY;
    this.HUB_R = HUB_R;
    this.NODE_HALF_W = NODE_HALF_W; this.NODE_HALF_H = NODE_HALF_H; this.NODE_RX = NODE_RX;
    this.RESOURCE_ICON_R = RESOURCE_ICON_R; this.LIVE_DOT_R = LIVE_DOT_R;
  },
});
