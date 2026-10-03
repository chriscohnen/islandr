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
// topology-resource-ring: row height/icon radius bumped from 22/8 to 32/10
// (decided against the brief's proposed radius 14, which would have forced
// 40px rows under the existing row-height-follows-icon-size rule — 10/32 is
// still more generous than before without that knock-on change).
const RESOURCE_ROW_H = 32;       // vertical spacing between resource rows
const RESOURCE_ICON_R = 10;      // small icon circle at the start of each row
const RESOURCE_LIST_GAP = 50;    // network box edge → row icons, horizontal
const RESOURCE_SPINE_GAP = 22;   // network box edge → the vertical tree spine
const RESOURCE_LABEL_W = 130;    // approx label width, for viewBox sizing only
const RESOURCE_TREE_CORNER_R = 8; // corner radius where a branch leaves the spine
// topology-expanded-site-columns: pitch between columns once a site's
// resources wrap past one column — icon width plus the label's own
// approximate width, so a column's labels never run into the next column's
// icons.
const RESOURCE_COLUMN_PITCH = RESOURCE_LABEL_W + RESOURCE_ICON_R * 2;
const LIVE_DOT_R = 4;
const LIVE_DOT_ORBIT = 78; // inside FIRST_RING, but far enough out for a name+IP label under the dot
// topology-peer-detail-levels: three tiers by peer count, matching the
// backend's TOPOLOGY_LIVE_PEER_CAP (40) — beyond that the backend itself
// stops shipping individual peers, so "orbit" and "collapsed" must share
// that exact boundary or the diagram would silently under-report.
const PEER_TIER1_MAX = 8;   // "wenige Peers" — device icon + status ring each
const PEER_TIER2_MAX = 40;  // "viele Peers" — small dots on an orbit, hover for detail
const PEER_ICON_R = 11;     // tier-1 device icon radius — bigger than a live dot, smaller than a resource row
const PEER_SUMMARY_DIST = FIRST_RING - 40; // tier-3 collapsed node position, inside the site/gateway ring

// The viewBox fits tightly around whatever's on screen (see contentBBox) but
// is capped here so a big topology doesn't shrink nodes/labels into
// unreadable specks — past this size the viewBox stops growing and the user
// drags to pan across the (larger) content instead.
const MAX_VIEW_W = 900;
const MAX_VIEW_H = 620;
const PAN_SLACK = 40; // a little overscroll room at the content's own edges
const DRAG_THRESHOLD_PX = 4; // pointer movement before a press counts as a pan, not a click
// contentBBox pads the tight content bounds by this much on all four sides —
// also reused by the "click to expand" corner hint below, which used to sit
// on its own tighter 12px inset. That mismatch (12 vs this 24) was the
// diagram's actual "not centered" bug: the bbox math itself was already
// symmetric (proven — see contentBBox), but the hint text hugged the
// bottom-right corner closer than any real node/label ever could, making
// the bottom of the canvas visibly more cramped than the top in the default
// (nothing expanded) view where the hint is shown.
const CONTENT_PAD = 24;

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

// Path through a list of axis-aligned points, each interior corner rounded
// to radius `r` (clamped to half the shorter of its two adjacent segments,
// so a tight corner never overshoots past the next point). A quadratic
// bezier through the exact corner point is a close enough approximation of
// a circular fillet at this scale and far simpler than computing an
// elliptical-arc sweep flag for each of the four possible turn directions.
// Degenerates cleanly to a straight line when two consecutive points
// coincide (topology-resource-ring's "a single resource needs no spine at
// all" case) — the zero-length segment just produces a zero-radius corner.
function roundedOrthogonalPath(points, r) {
  if (points.length < 2) return "";
  if (points.length === 2) return `M${points[0].x},${points[0].y} L${points[1].x},${points[1].y}`;
  let d = `M${points[0].x},${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1], cur = points[i], next = points[i + 1];
    const segIn = Math.hypot(cur.x - prev.x, cur.y - prev.y);
    const segOut = Math.hypot(next.x - cur.x, next.y - cur.y);
    const rr = Math.min(r, segIn / 2, segOut / 2);
    const inPoint = rr === 0 ? cur : {
      x: cur.x + ((prev.x - cur.x) / segIn) * rr,
      y: cur.y + ((prev.y - cur.y) / segIn) * rr,
    };
    const outPoint = rr === 0 ? cur : {
      x: cur.x + ((next.x - cur.x) / segOut) * rr,
      y: cur.y + ((next.y - cur.y) / segOut) * rr,
    };
    d += ` L${inPoint.x},${inPoint.y} Q${cur.x},${cur.y} ${outPoint.x},${outPoint.y}`;
  }
  const last = points[points.length - 1];
  d += ` L${last.x},${last.y}`;
  return d;
}

export default defineComponent({
  name: "TopologyDiagram",
  props: {
    sites:            { type: Array,  required: true },
    resources:        { type: Array,  required: true },
    livePeers:        { type: Array,  default: () => [] },
    // topology-peer-detail-levels: uncapped connected/stale/disconnected
    // totals behind livePeers — null from callers that don't track peers at
    // all (the portal's "my resources" reuse of this component).
    peerStatusCounts: { type: Object,  default: null },
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
      tooltip: null,         // { resource, reachability, x, y }
      networkTooltip: null,  // { site, x, y }
      gatewayTooltip: null,  // { gateway, x, y }
      peerTooltip: null,     // { peer, x, y } — topology-peer-detail-levels tier 2 hover
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
    //
    // topology-expanded-site-columns: wraps into multiple columns once a
    // single column would run past the capped viewBox height — one column
    // at the full TOPOLOGY_RESOURCE_CAP (24) used to overlay the hub and
    // neighboring nodes. maxRowsPerCol is derived from MAX_VIEW_H (the
    // capped viewBox height), not guessed, so a small window and a large
    // one wrap at the same, always-visible row count. Columns are balanced
    // (evenly split across the computed column count) rather than "fill the
    // first column to the brim, spill the rest" — every column ends up a
    // similar height, easier to scan. Each row carries its own `col` and
    // `spineX` so resourceBranchPath can draw a separate tree per column.
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
      // topology-resource-ring: gateway liveness, not a per-resource check —
      // "Mein Zugang" uses the same source since myaccess-reachability-indicator
      // (null = no gateway configured for this site, i.e. unknown rather than
      // down). net.site is the raw SiteDto, carrying gatewayOnline regardless
      // of whether this network is a direct spoke or grouped under a gateway.
      const gatewayOnline = net.site.gatewayOnline;

      const maxRowsPerCol = Math.max(1, Math.floor((MAX_VIEW_H - 2 * CONTENT_PAD) / RESOURCE_ROW_H));
      const numCols = Math.max(1, Math.ceil(list.length / maxRowsPerCol));
      const rowsPerCol = Math.ceil(list.length / numCols);

      const out = [];
      for (let col = 0; col < numCols; col++) {
        const colItems = list.slice(col * rowsPerCol, (col + 1) * rowsPerCol);
        if (colItems.length === 0) continue;
        const totalH = colItems.length * RESOURCE_ROW_H;
        const startY = net.y - totalH / 2 + RESOURCE_ROW_H / 2;
        const iconX = net.x + dir * (NODE_HALF_W + RESOURCE_LIST_GAP + col * RESOURCE_COLUMN_PITCH);
        const spineX = net.x + dir * (NODE_HALF_W + RESOURCE_SPINE_GAP + col * RESOURCE_COLUMN_PITCH);
        colItems.forEach((r, i) => {
          out.push({
            resource: r, netX: net.x, netY: net.y, dir, gatewayOnline, col, spineX,
            x: iconX, y: startY + i * RESOURCE_ROW_H,
          });
        });
      }
      return out;
    },
    // A y clear of every row in every column — one row-height above the
    // topmost row across the whole fan, not just one column. Used only by
    // columns past the first to detour around column 0's icons instead of
    // cutting through them (see resourceBranchPath).
    resourceColumnBypassY() {
      if (this.resourceLayout.length === 0) return null;
      const ys = this.resourceLayout.map((r) => r.y);
      return Math.min(...ys) - RESOURCE_ROW_H;
    },
    // Site-type peers are already represented by their gateway node (ring color
    // shows status) — excluded from every tier below to avoid confusing
    // duplicate dots.
    relevantLivePeers() {
      return this.livePeers.filter((p) => p.type !== "site");
    },
    // topology-peer-detail-levels: which of the three detail tiers applies,
    // driven by the *uncapped* connected+stale total (peerStatusCounts) —
    // not livePeers.length, which is capped at PEER_TIER2_MAX and would
    // under-report once a hub actually has that many peers. "none" when
    // there's nothing live at all (nothing to draw) and "icons"/"collapsed"
    // fall back sensibly when peerStatusCounts itself wasn't provided (the
    // portal's "my resources" reuse of this component never shows peers).
    peerTier() {
      if (!this.peerStatusCounts) return this.relevantLivePeers.length === 0 ? "none" : "icons";
      const total = (this.peerStatusCounts.connected || 0) + (this.peerStatusCounts.stale || 0);
      if (total === 0) return "none";
      if (total <= PEER_TIER1_MAX) return "icons";
      if (total <= PEER_TIER2_MAX) return "orbit";
      return "collapsed";
    },
    // Tier 1 ("wenige Peers") and tier 2 ("viele Peers") share this layout —
    // only the dot size/label visibility differ in the template. Tier 2
    // sorts connected-before-stale so the two groups read as two arcs
    // rather than an arbitrary interleave.
    livePeerLayout() {
      if (this.peerTier !== "icons" && this.peerTier !== "orbit") return [];
      let list = this.relevantLivePeers;
      if (this.peerTier === "orbit") {
        list = [...list].sort((a, b) => {
          if (a.connectionStatus === b.connectionStatus) return 0;
          return a.connectionStatus === "CONNECTED" ? -1 : 1;
        });
      }
      return list.map((p, i) => {
        const angle = angleAt(i, list.length, -135);
        return { peer: p, x: CX + LIVE_DOT_ORBIT * Math.cos(angle), y: CY + LIVE_DOT_ORBIT * Math.sin(angle) };
      });
    },
    // Tier 3 ("eingeklappt") position — a single fixed point rather than
    // something derived from gatewayLayout/visibleNetworks, since there's
    // exactly one of these regardless of how many peers it summarizes.
    peerSummaryLayout() {
      if (this.peerTier !== "collapsed") return null;
      return { x: CX, y: CY + PEER_SUMMARY_DIST };
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
      // The column-2+ bypass line (resourceBranchPath) runs a row-height
      // above the topmost row — pad for it explicitly, or a multi-column
      // site clips its own connecting line at the top of the viewBox.
      if (this.resourceColumnBypassY !== null && this.resourceLayout.some((r) => r.col > 0)) {
        pts.push({ x: this.resourceLayout[0].netX, y: this.resourceColumnBypassY });
      }
      for (const d of this.livePeerLayout) {
        // Tier 1 ("icons") always shows two label lines (name + IP) below a
        // bigger device icon; tier 2 ("orbit") is a small dot with no
        // permanent label (hover-only) — pad each for what it actually draws.
        const r = this.peerTier === "icons" ? PEER_ICON_R : LIVE_DOT_R;
        const labelPad = this.peerTier === "icons" ? 26 : 4;
        pts.push({ x: d.x - 40, y: d.y - r });
        pts.push({ x: d.x + 40, y: d.y + r + labelPad });
      }
      if (this.peerSummaryLayout) {
        const s = this.peerSummaryLayout;
        pts.push({ x: s.x - NODE_HALF_W, y: s.y - NODE_HALF_H });
        pts.push({ x: s.x + NODE_HALF_W, y: s.y + NODE_HALF_H + 14 });
      }

      const PAD = CONTENT_PAD;
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
    // topology-resource-ring: a closed circle, not the previous ~160° open
    // arc (which read as a loading spinner) — so this just needs the branch
    // line's endpoint now, not an SVG path. See resourceBranchPath for that
    // line itself.
    //
    // One rounded-corner path per resource row: network box → spine-entry
    // corner → this row's corner → the ring's outer edge (not the icon's
    // center — the line must end at the ring, not run on underneath it).
    // Every row's path shares its first two points with every other row's
    // (identical geometry, renders as one overlapping line) — this replaces
    // the old trio of independent trunk/spine/branch <line> elements, which
    // only *looked* cornered where two unrelated lines happened to cross; an
    // actually rounded join needs one continuous path instead. A lone
    // resource in its column needs no spine at all — net box and icon
    // already sit on the same y (see resourceLayout's startY math), and
    // roundedOrthogonalPath already degenerates a coincident-point corner to
    // a straight line, so no special case is needed here either.
    // topology-expanded-site-columns: spineX is per-row now (one spine per
    // column, item.col/item.spineX set in resourceLayout) instead of a
    // single diagram-wide resourceSpineX.
    //
    // Column 0 connects directly, same as the single-column case always did
    // — its spine sits between the network box and its own icons, so a
    // straight horizontal entry at netY never crosses anything.
    //
    // Column 1+ does NOT cut straight across at netY to its own (much
    // further out) spine — that line would run at the same y as every
    // row in column 0, slicing straight through whichever one happens to
    // sit near netY (bug report 2026-10-03, screenshot: the line to column
    // 2 ran directly through "adguard" in column 1). Instead it enters via
    // column 0's spine, detours to a bypass y clear of every column's rows
    // (above the whole fan), travels across at that safe height, then drops
    // down to its own spine and row.
    resourceBranchPath(item) {
      const iconEdgeX = item.dir > 0 ? item.x - RESOURCE_ICON_R : item.x + RESOURCE_ICON_R;
      const spine0X = item.netX + item.dir * (NODE_HALF_W + RESOURCE_SPINE_GAP);
      if (item.col === 0) {
        const points = [
          { x: item.netX, y: item.netY },
          { x: spine0X, y: item.netY },
          { x: spine0X, y: item.y },
          { x: iconEdgeX, y: item.y },
        ];
        return roundedOrthogonalPath(points, RESOURCE_TREE_CORNER_R);
      }
      const bypassY = this.resourceColumnBypassY;
      const points = [
        { x: item.netX, y: item.netY },
        { x: spine0X, y: item.netY },
        { x: spine0X, y: bypassY },
        { x: item.spineX, y: bypassY },
        { x: item.spineX, y: item.y },
        { x: iconEdgeX, y: item.y },
      ];
      return roundedOrthogonalPath(points, RESOURCE_TREE_CORNER_R);
    },
    // topology-resource-ring: "Mein Zugang" wording for the same tri-state
    // (myaccess-reachability-indicator) — null/true/false here map to
    // "unknown" (no gateway at this site at all)/"live"/"down".
    resourceReachability(item) {
      if (item.gatewayOnline === true) return "live";
      if (item.gatewayOnline === false) return "down";
      return "unknown";
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
    // topology-peer-detail-levels, tier 1: device-category icon per peer,
    // same fallback convention as the admin heatmap's peerIconName — an
    // unset/"other" device falls back to the generic "peers" glyph rather
    // than a made-up default.
    peerDeviceIconMarkup(deviceType) {
      const key = deviceType && deviceType !== "other" ? deviceType : "peers";
      const paths = ICON_PATHS[key] || ICON_PATHS.peers;
      return paths.join("");
    },
    peerDeviceIconTitle(peer) {
      const labels = {
        laptop: t("peers.dev_laptop"), desktop: t("peers.dev_desktop"), mobile: t("peers.dev_mobile"),
        tablet: t("peers.dev_tablet"), server: t("peers.dev_server"), other: t("peers.dev_other"),
      };
      return labels[peer.deviceType] || t("peers.dev_other");
    },
    // "live" | "stale" — the CSS class suffix for a peer node/dot's ring.
    // Never "disconnected": that status is never shipped in livePeers at
    // all (Variante B), so this function's callers never see it. Defaults
    // to "live" rather than branching on an exact "CONNECTED" match — the
    // liveMode polling overlay (/api/v1/peers/live) has no connectionStatus
    // field at all, and every peer it returns handshook within the last 3
    // minutes anyway, so "not explicitly stale" is the correct read there.
    peerStatusClass(peer) {
      return peer.connectionStatus === "STALE" ? "stale" : "live";
    },
    peerTooltipDetail(peer) {
      const status = peer.connectionStatus === "CONNECTED"
          ? t("topology.peer_status_connected") : t("topology.peer_status_stale");
      return `${status} · ${relativeTime(peer.lastSeenAt)}`;
    },
    showPeerTooltip(event, peer) {
      const rect = this.$el.getBoundingClientRect();
      this.peerTooltip = { peer, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    movePeerTooltip(event) {
      if (!this.peerTooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.peerTooltip = { ...this.peerTooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hidePeerTooltip() { this.peerTooltip = null; },
    // Tier 3's distribution bar: width in px of one segment of a 64px-wide
    // bar, proportional to that status's share of the total. Disconnected
    // isn't drawn at all — the bar visualizes "what's reachable right now"
    // (connected/stale), not the full roster; the numbers above it already
    // carry the disconnected count.
    peerSummaryBarSegment(key) {
      const c = this.peerStatusCounts;
      if (!c) return 0;
      const total = c.connected + c.stale + c.disconnected;
      if (total === 0) return 0;
      return Math.round((c[key] / total) * 64);
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
      // A gateway group left open while an unrelated site drills into its
      // resource columns clutters the diagram and can visually collide with
      // it (bug report 2026-10-03: a resource-column connecting line ran
      // across an unrelated, still-expanded gateway node). Only clear it
      // when the newly expanded site isn't itself one of that gateway's own
      // members — collapsing the gateway in that case would remove the very
      // network box the user just clicked.
      if (this.expandedGatewayId && site.gatewayPeerId !== this.expandedGatewayId) {
        this.expandedGatewayId = null;
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
    showTooltip(event, resource, reachability) {
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { resource, reachability, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
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
    // Bug report 2026-10-03: a resource column's connecting line could cross
    // an unrelated, still-expanded gateway node — fixed primarily by onNetworkClick
    // collapsing an unrelated gateway and by drawing the resource tree after
    // every other node (see the template's own comment near that block), but
    // dimming every node that isn't part of the active drilldown also makes
    // it the clear visual focus instead of just technically on top. `fallback`
    // keeps each node's own pre-existing opacity rule (e.g. an empty network
    // box) for when nothing is expanded at all.
    nodeFocusStyle(isRelevant, fallback) {
      if (this.expandedSiteId && !isRelevant) return "opacity: 0.35";
      return fallback || "";
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

      <!-- Anchored to the bottom of the .topo SVG's own box (not the whole
           card) and below the hub, rather than centered over the whole
           area — centering it there put the hint text directly on top of
           the hub circle/label when there's nothing else on the diagram to
           push it aside. The hub sits at the vertical center of this same
           box (see CX/CY), so bottom-anchoring always clears it. -->
      <div v-if="sites.length === 0" class="topo-empty"
           style="position: absolute; left: 0; right: 0; top: 0; height: min(440px, 55vh); display: flex; align-items: flex-end; justify-content: center; padding-bottom: 20px; pointer-events: none; z-index: 1">
        <span style="pointer-events: auto; text-align: center; max-width: 480px">{{ t('topology.empty_a') }}<router-link to="/networks" style="font-weight: 600; color: var(--fg1); text-decoration: underline">{{ t('nav.networks') }}</router-link>{{ t('topology.empty_b') }}</span>
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
              :style="nodeFocusStyle(item.gateway.gatewayPeerId === expandedGatewayId)"
              :d="linkPath(CX, CY, item.x, item.y)" />
        <path v-for="item in directNetworkLayout" :key="'dl-'+item.site.id"
              class="link"
              :style="nodeFocusStyle(item.site.id === expandedSiteId)"
              :d="linkPath(CX, CY, item.x, item.y)" />

        <!-- Gateway-to-network links (expanded gateway only) -->
        <path v-for="item in expandedGatewayNetworkLayout" :key="'gnl-'+item.site.id"
              class="link"
              :style="nodeFocusStyle(item.site.id === expandedSiteId, 'opacity:0.45')"
              :d="linkPath(item.parentX, item.parentY, item.x, item.y)" />

        <!-- Hub -->
        <circle class="hub-pulse" :cx="CX" :cy="CY" :r="HUB_R" />
        <circle class="hub-core"  :cx="CX" :cy="CY" :r="HUB_R - 6" />

        <!-- Brand mark (constellation), knocked out in --fg-on-accent so it
             reads on the accent-filled hub circle in both themes — same mark
             as favicon.svg/islandr-mark.svg, flattened and scaled to fit the
             hub-core radius. Purely decorative: no title/pointer-events, the
             hub-core circle underneath still carries any future interaction. -->
        <g class="hub-mark" :transform="'translate('+(CX-22.68)+','+(CY-22.68)+') scale(0.36)'" style="pointer-events:none">
          <path d="M27 102 L53 90 L71 64 L99 46 M71 64 L65 33" fill="none" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round" />
          <circle cx="27" cy="102" r="5" />
          <circle cx="53" cy="90" r="4.5" />
          <circle cx="71" cy="64" r="6" />
          <circle cx="99" cy="46" r="5" />
          <circle cx="65" cy="33" r="9" />
        </g>

        <text   class="hub-label" :x="CX" :y="CY + HUB_R + 16">{{ hubLabel || 'Hub' }}</text>
        <text v-if="endpoint" class="hub-endpoint" :x="CX" :y="CY + HUB_R + 30">{{ endpoint }}</text>

        <!-- topology-peer-detail-levels: live-peer links, drawn for tier 1
             ("icons") and tier 2 ("orbit") alike — these come and go with
             recent handshake activity, so the link is thin/dashed-feeling
             (link-live) unlike the static topology below. -->
        <path v-for="d in livePeerLayout" :key="'ll-'+d.peer.id"
              :class="['link-live', d.peer.trafficTier === 'flowing' ? 'link-flowing' : '', d.peer.trafficTier === 'flowing-heavy' ? 'link-flowing-heavy' : '']"
              :d="linkPath(CX, CY, d.x, d.y, 0.1)" />

        <!-- Tier 1 ("wenige Peers", ≤8): a real device icon + status ring,
             same visual language as a resource row — name + IP always
             shown, no tooltip needed at this count. -->
        <template v-if="peerTier === 'icons'">
          <g v-for="d in livePeerLayout" :key="'lp-'+d.peer.id"
             :class="['node', 'peer', 'status-' + peerStatusClass(d.peer)]"
             :transform="'translate('+d.x+','+d.y+')'">
            <title>{{ peerDeviceIconTitle(d.peer) }}</title>
            <circle class="node-bg" :r="PEER_ICON_R - 2" />
            <circle class="node-ring" fill="none" :r="PEER_ICON_R" />
            <g class="node-icon" transform="translate(-6,-6) scale(0.5)"
               fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round"
               v-html="peerDeviceIconMarkup(d.peer.deviceType)" />
            <text class="live-label" :y="PEER_ICON_R + 12">{{ d.peer.name || t('topology.unknown_peer') }}</text>
            <text v-if="d.peer.assignedIp" class="live-ip" :y="PEER_ICON_R + 24">{{ d.peer.assignedIp }}</text>
          </g>
        </template>

        <!-- Tier 2 ("viele Peers", 9–40): small dots only, sorted connected-
             before-stale — name/IP/status/DNS name only on hover, or the
             orbit turns back into the same label clutter tier 1 avoids. -->
        <template v-else-if="peerTier === 'orbit'">
          <circle v-for="d in livePeerLayout" :key="'lp-'+d.peer.id"
                  :cx="d.x" :cy="d.y" :r="LIVE_DOT_R"
                  :class="['node', 'peer', 'status-' + peerStatusClass(d.peer)]"
                  @mouseenter="showPeerTooltip($event, d.peer)"
                  @mousemove="movePeerTooltip($event)"
                  @mouseleave="hidePeerTooltip" />
        </template>

        <!-- Tier 3 ("eingeklappt", >40): a single summary node, same box
             style as a network node, with the three counts and a simple
             proportional bar — numbers are the actual status indicator here
             (never color alone), the bar just reinforces it. -->
        <g v-if="peerSummaryLayout" class="node network"
           :transform="'translate('+peerSummaryLayout.x+','+peerSummaryLayout.y+')'">
          <rect class="node-ring" :x="-NODE_HALF_W" :y="-NODE_HALF_H"
                :width="NODE_HALF_W*2" :height="NODE_HALF_H*2" :rx="NODE_RX" />
          <rect class="node-bg" :x="-NODE_HALF_W+2" :y="-NODE_HALF_H+2"
                :width="NODE_HALF_W*2-4" :height="NODE_HALF_H*2-4" :rx="NODE_RX-2" />
          <text class="node-label mono" y="4">{{ peerStatusCounts.connected }}/{{ peerStatusCounts.stale }}/{{ peerStatusCounts.disconnected }}</text>
          <text class="hub-label" :y="NODE_HALF_H + 16">{{ t('topology.peer_summary_title') }}</text>
          <g :transform="'translate('+(-NODE_HALF_W+4)+','+(NODE_HALF_H+22)+')'">
            <rect class="peer-summary-bar-bg" width="64" height="4" rx="2" />
            <rect class="peer-summary-bar-connected" height="4" rx="2"
                  :width="peerSummaryBarSegment('connected')" />
            <rect class="peer-summary-bar-stale" height="4" rx="2"
                  :x="peerSummaryBarSegment('connected')" :width="peerSummaryBarSegment('stale')" />
          </g>
          <title>{{ t('topology.peer_summary_title') }}: {{ peerStatusCounts.connected }} {{ t('topology.peer_status_connected') }}, {{ peerStatusCounts.stale }} {{ t('topology.peer_status_stale') }}, {{ peerStatusCounts.disconnected }} {{ t('topology.peer_status_disconnected') }}</title>
        </g>

        <!-- Gateway-peer nodes — router silhouette (box, not circle): a shared
             site router groups every network routed through it into one spoke. -->
        <g v-for="item in gatewayLayout" :key="item.gateway.gatewayPeerId"
           :class="['node', item.gateway.gatewayOnline ? 'live' : 'disabled']"
           :style="nodeFocusStyle(item.gateway.gatewayPeerId === expandedGatewayId)"
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
           :style="nodeFocusStyle(item.site.id === expandedSiteId, !item.expanded && item.count === 0 ? 'opacity: 0.55' : '')"
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

        <!-- Network-to-resource tree (expanded network only), drawn AFTER
             every hub/gateway/network node above so it paints on top of
             them instead of under — bug report 2026-10-03: a column-2+
             connecting line visually ran underneath an unrelated gateway
             node it happened to cross. One rounded-corner path per resource
             row, network box → spine → this row's icon — see
             resourceBranchPath's own doc comment for why this is one path
             per row instead of three shared <line> elements. -->
        <path v-for="item in resourceLayout" :key="'rl-'+item.resource.id"
              class="link" style="opacity:0.45"
              :d="resourceBranchPath(item)" />

        <!-- Resource rows (expanded network only) — icon + name, "netzplan"
             list style. Hover still surfaces IP/ports via the same tooltip
             as before, just triggered off a row instead of a circle.
             topology-resource-ring: status comes from the site's gateway
             (resourceReachability) — "unknown" (no gateway at this site)
             keeps the old neutral accent ring, "down" additionally dashes
             the ring and dims icon/label (never color-only), "live" is a
             closed, solid green ring. -->
        <g v-for="item in resourceLayout" :key="item.resource.id"
           :class="['node', 'resource', 'reachability-' + resourceReachability(item)]"
           @click="onResourceClick(item.resource.siteId, item.resource.id)"
           @mouseenter="showTooltip($event, item.resource, resourceReachability(item))"
           @mousemove="moveTooltip($event)"
           @mouseleave="hideTooltip"
           :transform="'translate('+item.x+','+item.y+')'">
          <circle class="node-bg" :r="RESOURCE_ICON_R - 2" />
          <circle class="node-ring" fill="none" :r="RESOURCE_ICON_R" />
          <g class="node-icon" transform="translate(-6,-6) scale(0.5)"
             fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round"
             v-html="resourceIconMarkup(item.resource.type)" />
          <text class="node-label"
                :x="item.dir > 0 ? RESOURCE_ICON_R + 6 : -(RESOURCE_ICON_R + 6)"
                y="4"
                :style="'text-anchor:' + (item.dir > 0 ? 'start' : 'end')">{{ item.resource.name }}</text>
        </g>

        <!-- Hint -->
        <text v-if="!expandedGatewayId && !expandedSiteId" :x="viewBoxRect.x + viewBoxRect.w - CONTENT_PAD" :y="viewBoxRect.y + viewBoxRect.h - CONTENT_PAD"
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
        <!-- topology-resource-ring: this is gateway liveness, not a check of
             the resource itself — the wording says so explicitly, or a green
             dot here would promise more than the data backs up. -->
        <div style="font-size: var(--text-xs); margin-bottom: 4px">
          <span :style="'color:' + (tooltip.reachability === 'live' ? 'var(--status-ok)' : 'var(--fg3)')"
                style="font-size:9px">{{ tooltip.reachability === 'live' ? '●' : tooltip.reachability === 'down' ? '◌' : '○' }}</span>
          <span :style="'color:' + (tooltip.reachability === 'down' ? 'var(--fg3)' : 'var(--fg2)')">
            {{ tooltip.reachability === 'live' ? t('topology.resource_gateway_live')
               : tooltip.reachability === 'down' ? t('topology.resource_gateway_down')
               : t('topology.resource_gateway_unknown') }}
          </span>
        </div>
        <div v-if="tooltip.resource.portLabels && tooltip.resource.portLabels.length > 0"
             style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg3); line-height: 1.5">
          <div v-for="p in tooltip.resource.portLabels" :key="p">{{ p }}</div>
        </div>
        <div v-else style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
          {{ t('topology.no_ports') }}
        </div>
      </div>

      <!-- topology-peer-detail-levels, tier 2: hover detail for an orbit
           dot — device icon, name, IP, status with relative time, and the
           DNS short name when the resolver is on (never a raw peer name —
           dnsFqdn is already the slugified form). -->
      <div v-if="peerTooltip" :style="{
             position: 'absolute',
             left: peerTooltip.x + 'px',
             top: peerTooltip.y + 'px',
             pointerEvents: 'none',
             zIndex: 10,
             background: 'var(--surface-2)',
             border: '1px solid var(--border)',
             borderRadius: 'var(--radius-sm)',
             padding: '6px 10px',
             boxShadow: 'var(--shadow-md, 0 4px 12px rgba(0,0,0,0.3))',
             maxWidth: '220px',
           }">
        <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); margin-bottom: 4px; display: flex; align-items: center; gap: 6px">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"
               class="node-icon" style="flex-shrink: 0" v-html="peerDeviceIconMarkup(peerTooltip.peer.deviceType)"></svg>
          {{ peerTooltip.peer.name || t('topology.unknown_peer') }}
        </div>
        <div v-if="peerTooltip.peer.assignedIp" class="mono" style="font-size: var(--text-xs); color: var(--fg2); margin-bottom: 2px">
          {{ peerTooltip.peer.assignedIp }}
        </div>
        <div style="font-size: var(--text-xs); color: var(--fg2); margin-bottom: 2px">
          {{ peerTooltipDetail(peerTooltip.peer) }}
        </div>
        <div v-if="peerTooltip.peer.dnsFqdn" class="mono" style="font-size: var(--text-xs); color: var(--fg3)">
          {{ peerTooltip.peer.dnsFqdn }}
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
    this.PEER_ICON_R = PEER_ICON_R;
    this.CONTENT_PAD = CONTENT_PAD;
  },
});
