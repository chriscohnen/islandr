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
const RESOURCE_OFFSET = 110;     // network → its resources (added to the network's own hub distance)
const NODE_HALF_W = 24;          // gateway/network box half-width
const NODE_HALF_H = 14;          // gateway/network box half-height
const NODE_RX = 8;
const RESOURCE_R = 18;
const LIVE_DOT_R = 4;
const LIVE_DOT_ORBIT = 78; // inside FIRST_RING, but far enough out for a name+IP label under the dot

// The viewBox fits tightly around whatever's on screen (see contentBBox) but
// is capped here so a big topology doesn't shrink nodes/labels into
// unreadable specks — past this size the viewBox stops growing and the user
// drags to pan across the (larger) content instead.
const MAX_VIEW_W = 900;
const MAX_VIEW_H = 620;
const PAN_SLACK = 40; // a little overscroll room at the content's own edges

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

export default defineComponent({
  name: "TopologyDiagram",
  props: {
    sites:            { type: Array,  required: true },
    resources:        { type: Array,  required: true },
    livePeers:        { type: Array,  default: () => [] },
    resourceOverflow: { type: Number, default: 0 },
    endpoint:         { type: String, default: "" },
    hubLabel:         { type: String, default: "" },
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
    resourceLayout() {
      if (!this.expandedSiteId) return [];
      const net = this.visibleNetworks.find((n) => n.site.id === this.expandedSiteId);
      if (!net) return [];
      const cached = this.siteResourceCache[this.expandedSiteId];
      let list = cached
        ? (this.activeTypes.size === 0 ? cached : cached.filter((r) => this.activeTypes.has(r.type || "computer")))
        : this.filteredResources.filter((r) => r.siteId === this.expandedSiteId);
      if (list.length === 0) return [];
      const angles = fanAngles(net.angle, list.length);
      // Push the ring outward for busy sites so the wider fan (see fanAngles)
      // still leaves breathing room between adjacent resource nodes instead
      // of just spreading a fixed-radius circle thinner.
      const dist = net.dist + RESOURCE_OFFSET + Math.max(0, list.length - 10) * 4;
      return list.map((r, i) => {
        const { x, y } = polar(angles[i], dist);
        return { resource: r, netX: net.x, netY: net.y, x, y };
      });
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
      const addResource = (x, y) => {
        pts.push({ x: x - RESOURCE_R, y: y - RESOURCE_R });
        pts.push({ x: x + RESOURCE_R, y: y + RESOURCE_R + 14 });
      };
      for (const item of this.gatewayLayout) addBox(item.x, item.y, item.gateway.sites.length > 1 ? 2 : 1);
      for (const item of this.visibleNetworks) addBox(item.x, item.y, item.expanded ? 2 : 1);
      for (const item of this.resourceLayout) addResource(item.x, item.y);
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
    // Drag-to-pan, mouse and touch alike via Pointer Events. Only does
    // anything once the content no longer fits the capped viewBox
    // (needsPan) — otherwise everything's already visible and there's
    // nothing to pan to.
    onPointerDown(e) {
      if (!this.needsPan) return;
      this.dragging = true;
      this.dragStartPointer = { x: e.clientX, y: e.clientY };
      this.dragStartPan = { x: this.panX, y: this.panY };
      e.currentTarget.setPointerCapture(e.pointerId);
    },
    onPointerMove(e) {
      if (!this.dragging) return;
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
      this.dragging = false;
      this.dragStartPointer = null;
      this.dragStartPan = null;
      try { e.currentTarget.releasePointerCapture(e.pointerId); } catch { /* already released */ }
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
          style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px">
          {{ t('topology.filter_all') }}
        </button>
        <button v-for="tp in presentTypes" :key="tp.key"
          @click="toggleType(tp.key)"
          :class="['btn','btn-sm', activeTypes.has(tp.key) ? 'btn-secondary' : 'btn-ghost']"
          style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px">
          {{ t(tp.labelKey) }}
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

        <!-- Hub-to-gateway / hub-to-direct-network links -->
        <line v-for="item in gatewayLayout" :key="'gl-'+item.gateway.gatewayPeerId"
              class="link" :x1="CX" :y1="CY" :x2="item.x" :y2="item.y"
              :style="item.gateway.gatewayOnline ? '' : 'stroke-dasharray: 4 4; opacity: 0.55'" />
        <line v-for="item in directNetworkLayout" :key="'dl-'+item.site.id"
              class="link" :x1="CX" :y1="CY" :x2="item.x" :y2="item.y" />

        <!-- Gateway-to-network links (expanded gateway only) -->
        <line v-for="item in expandedGatewayNetworkLayout" :key="'gnl-'+item.site.id"
              class="link" style="opacity:0.45"
              :x1="item.parentX" :y1="item.parentY" :x2="item.x" :y2="item.y" />

        <!-- Network-to-resource links (expanded network only) -->
        <line v-for="item in resourceLayout" :key="'rl-'+item.resource.id"
              class="link" style="opacity:0.45"
              :x1="item.netX" :y1="item.netY" :x2="item.x" :y2="item.y" />

        <!-- Hub -->
        <circle class="hub-pulse" :cx="CX" :cy="CY" :r="HUB_R" />
        <circle class="hub-core"  :cx="CX" :cy="CY" :r="HUB_R - 6" />
        <text   class="hub-label" :x="CX" :y="CY + HUB_R + 16">{{ hubLabel || 'Hub' }}</text>
        <text v-if="endpoint" class="hub-endpoint" :x="CX" :y="CY + HUB_R + 30">{{ endpoint }}</text>

        <!-- Live-peer links + dots + labels. Unlike the static topology below,
             these come and go with recent handshake activity, so the link is
             thin/dashed and the dot small — but the name + IP are printed
             right on the diagram, not hidden behind a hover-only tooltip. -->
        <line v-for="d in livePeerLayout" :key="'ll-'+d.peer.id"
              class="link-live" :x1="CX" :y1="CY" :x2="d.x" :y2="d.y" />
        <g v-for="d in livePeerLayout" :key="'lp-'+d.peer.id">
          <circle :cx="d.x" :cy="d.y" :r="LIVE_DOT_R" class="hub-core" style="opacity:0.85">
            <title>{{ d.peer.name || t('topology.unknown_peer') }} · {{ d.peer.assignedIp }} · {{ relativeTime(d.peer.lastSeenAt) }}</title>
          </circle>
          <text class="live-label" :x="d.x" :y="d.y + LIVE_DOT_R + 12">{{ d.peer.name || t('topology.unknown_peer') }}</text>
          <text v-if="d.peer.assignedIp" class="live-ip" :x="d.x" :y="d.y + LIVE_DOT_R + 24">{{ d.peer.assignedIp }}</text>
        </g>

        <!-- Resource nodes (expanded network only) -->
        <g v-for="item in resourceLayout" :key="item.resource.id"
           class="node live"
           @click="onResourceClick(item.resource.siteId, item.resource.id)"
           @mouseenter="showTooltip($event, item.resource)"
           @mousemove="moveTooltip($event)"
           @mouseleave="hideTooltip"
           :transform="'translate('+item.x+','+item.y+')'">
          <circle class="node-ring" :r="RESOURCE_R" />
          <circle class="node-bg"   :r="RESOURCE_R - 2" />
          <g class="node-icon" transform="translate(-9.6,-9.6) scale(0.8)"
             fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round"
             v-html="resourceIconMarkup(item.resource.type)" />
          <text class="node-label" :y="RESOURCE_R + 13">{{ item.resource.name }}</text>
        </g>

        <!-- Gateway-peer nodes — router silhouette (box, not circle): a shared
             site router groups every network routed through it into one spoke. -->
        <g v-for="item in gatewayLayout" :key="item.gateway.gatewayPeerId"
           class="node live"
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
           class="node live"
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
            <text style="font-family: var(--font-mono); font-size: 12px; font-weight: 700;
                         fill: var(--accent); text-anchor: middle; dominant-baseline: central;
                         user-select: none"
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
          <div style="margin-bottom: 2px">
            <span :style="gatewayTooltip.gateway.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                  style="font-size:9px">{{ gatewayTooltip.gateway.gatewayOnline ? '●' : '○' }}</span>
            <span style="font-family: var(--font-mono); color: var(--fg2)">{{ gatewayTooltip.gateway.gatewayIp }}</span>
          </div>
          <div>{{ gatewayTooltip.gateway.gatewayLastSeenAt ? t('topology.handshake', { when: relativeTime(gatewayTooltip.gateway.gatewayLastSeenAt) }) : t('topology.no_handshake') }}</div>
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
        <div style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg2)">
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
    this.RESOURCE_R = RESOURCE_R; this.LIVE_DOT_R = LIVE_DOT_R;
  },
});
