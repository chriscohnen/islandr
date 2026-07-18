import { defineComponent } from "vue";
import { PATHS as ICON_PATHS } from "/js/Icons.js";
import { t, relativeTime } from "/js/i18n.js";

// Two-ring radial topology with collapse/expand per site.
// Collapsed: site nodes show a resource-count number inside the circle.
// Click site → expands resources into outer ring, viewBox shifts to center
//   on that site. Click again → collapse.
// Type-filter chips narrow which resources count / appear.

const W = 720;
const H = 480;
const CX = W / 2;   // hub center X
const CY = H / 2;   // hub center Y
const HUB_R = 30;
const SITE_RING = 150;
const RESOURCE_RING = 260;
const SITE_R = 28;
const RESOURCE_R = 18;
const LIVE_DOT_R = 4;
const LIVE_DOT_ORBIT = 56;

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

function buildResourceLayout(sites, resources, expandedSiteId) {
  if (!expandedSiteId) return [];
  const siteIndex = sites.findIndex((s) => s.id === expandedSiteId);
  if (siteIndex === -1) return [];
  const list = resources.filter((r) => r.siteId === expandedSiteId);
  if (list.length === 0) return [];
  const siteAngle = angleAt(siteIndex, sites.length);
  const arcSpan = Math.min((2 * Math.PI) / 3, (list.length - 1) * 0.35 + 0.3);
  const arcStart = list.length === 1 ? siteAngle : siteAngle - arcSpan / 2;
  const step = list.length === 1 ? 0 : arcSpan / (list.length - 1);
  return list.map((r, i) => {
    const angle = arcStart + i * step;
    return {
      resource: r,
      siteIndex,
      angle,
      x: CX + RESOURCE_RING * Math.cos(angle),
      y: CY + RESOURCE_RING * Math.sin(angle),
    };
  });
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
      expandedSiteId: null,
      activeTypes: new Set(),
      vbX: 0,
      vbY: 0,
      tooltip: null,       // { resource, x, y }
      siteTooltip: null,   // { site, x, y }
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
    siteLayout() {
      return this.sites.map((s, i) => {
        const angle = angleAt(i, this.sites.length);
        const x = CX + SITE_RING * Math.cos(angle);
        const y = CY + SITE_RING * Math.sin(angle);
        const count = this.filteredResources.filter((r) => r.siteId === s.id).length;
        return { site: s, angle, x, y, count, expanded: s.id === this.expandedSiteId };
      });
    },
    resourceLayout() {
      return buildResourceLayout(this.sites, this.filteredResources, this.expandedSiteId);
    },
    livePeerLayout() {
      // Site-type peers are already represented by their site node (ring color shows
      // gateway status) — exclude them here to avoid confusing duplicate dots.
      return this.livePeers.filter(p => p.type !== "site").slice(0, 8).map((p, i) => {
        const angle = angleAt(i, 8, -135);
        return { peer: p, x: CX + LIVE_DOT_ORBIT * Math.cos(angle), y: CY + LIVE_DOT_ORBIT * Math.sin(angle) };
      });
    },
    viewBox() {
      return `${this.vbX} ${this.vbY} ${W} ${H}`;
    },
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    networkIconMarkup() {
      return (ICON_PATHS.networks || []).join("");
    },
    resourceIconMarkup(type) {
      const paths = ICON_PATHS[type || "computer"] || ICON_PATHS.computer;
      return paths.join("");
    },
    onSiteClick(site) {
      if (this.expandedSiteId === site.id) {
        // Collapse → reset viewBox to center
        this.expandedSiteId = null;
        this.vbX = 0;
        this.vbY = 0;
      } else {
        // Expand → shift viewBox so the site+its resources are centered
        this.expandedSiteId = site.id;
        const item = this.siteLayout.find((s) => s.site.id === site.id);
        if (item) {
          // Center the viewBox on the midpoint between hub and expanded site
          const focusX = (CX + item.x) / 2;
          const focusY = (CY + item.y) / 2;
          this.vbX = focusX - W / 2;
          this.vbY = focusY - H / 2;
        }
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
      this.tooltip = {
        resource,
        x: event.clientX - rect.left + 14,
        y: event.clientY - rect.top - 10,
      };
    },
    moveTooltip(event) {
      if (!this.tooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = {
        ...this.tooltip,
        x: event.clientX - rect.left + 14,
        y: event.clientY - rect.top - 10,
      };
    },
    hideTooltip() {
      this.tooltip = null;
    },
    showSiteTooltip(event, site) {
      if (!site.gatewayPeerId) return;
      const rect = this.$el.getBoundingClientRect();
      this.siteTooltip = {
        site,
        x: event.clientX - rect.left + 14,
        y: event.clientY - rect.top - 10,
      };
    },
    moveSiteTooltip(event) {
      if (!this.siteTooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.siteTooltip = { ...this.siteTooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hideSiteTooltip() {
      this.siteTooltip = null;
    },
    siteRingStyle(item) {
      if (item.expanded) return "stroke: var(--accent); stroke-width: 3";
      if (item.site.gatewayPeerId == null) return "";
      return item.site.gatewayOnline
        ? "stroke: var(--status-ok); stroke-width: 2.5"
        : "stroke: var(--fg3); stroke-width: 2";
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

      <div v-if="sites.length === 0" class="topo-empty"
           style="position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none; z-index: 1">
        <span style="pointer-events: auto">{{ t('topology.empty_a') }}<router-link to="/networks" style="font-weight: 600; color: var(--fg1); text-decoration: underline">{{ t('nav.networks') }}</router-link>{{ t('topology.empty_b') }}</span>
      </div>

      <svg class="topo" :viewBox="viewBox"
           style="transition: viewBox 0.3s ease"
           role="img" aria-label="Netzwerk-Topologie">

        <!-- Hub-to-Site links -->
        <line v-for="item in siteLayout" :key="'sl-'+item.site.id"
              class="link" :x1="CX" :y1="CY" :x2="item.x" :y2="item.y" />

        <!-- Site-to-Resource links -->
        <line v-for="item in resourceLayout" :key="'rl-'+item.resource.id"
              class="link" style="opacity:0.45"
              :x1="siteLayout[item.siteIndex].x" :y1="siteLayout[item.siteIndex].y"
              :x2="item.x" :y2="item.y" />

        <!-- Hub -->
        <circle class="hub-pulse" :cx="CX" :cy="CY" :r="HUB_R" />
        <circle class="hub-core"  :cx="CX" :cy="CY" :r="HUB_R - 6" />
        <text   class="hub-label" :x="CX" :y="CY + HUB_R + 16">{{ hubLabel || 'Hub' }}</text>
        <text v-if="endpoint" class="hub-endpoint" :x="CX" :y="CY + HUB_R + 30">{{ endpoint }}</text>

        <!-- Live-peer dots -->
        <circle v-for="d in livePeerLayout" :key="'lp-'+d.peer.id"
                :cx="d.x" :cy="d.y" :r="LIVE_DOT_R" class="hub-core" style="opacity:0.85">
          <title>{{ d.peer.name }} · {{ d.peer.assignedIp }} · {{ relativeTime(d.peer.lastSeenAt) }}</title>
        </circle>

        <!-- Resource nodes (expanded site only) -->
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

        <!-- Site nodes -->
        <g v-for="item in siteLayout" :key="item.site.id"
           class="node live"
           @click="onSiteClick(item.site)"
           @mouseenter="showSiteTooltip($event, item.site)"
           @mousemove="moveSiteTooltip($event)"
           @mouseleave="hideSiteTooltip"
           :transform="'translate('+item.x+','+item.y+')'">

          <!-- Outer ring — accent when expanded, gateway status color otherwise -->
          <circle class="node-ring" :r="SITE_R" :style="siteRingStyle(item)" />
          <circle class="node-bg" :r="SITE_R - 2" />

          <!-- Building glyph (top half of circle) OR count number (bottom) -->
          <!-- Show icon small at top, count large centered when collapsed -->
          <g v-if="!item.expanded">
            <!-- Networks icon, shifted upward to make room for number -->
            <g class="node-icon" transform="translate(-6,-14) scale(0.5)"
               fill="none" stroke="currentColor" stroke-width="2.5"
               stroke-linecap="round" stroke-linejoin="round"
               v-html="networkIconMarkup()" />
            <!-- Count number, centered vertically in lower portion -->
            <text style="font-family: var(--font-mono); font-size: 13px; font-weight: 700;
                         fill: var(--accent); text-anchor: middle; dominant-baseline: central;
                         user-select: none"
                  y="8">{{ item.count }}</text>
          </g>

          <!-- When expanded: networks icon centered -->
          <g v-else class="node-icon"
             transform="translate(-9.6,-9.6) scale(0.8)"
             fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round"
             v-html="networkIconMarkup()" />

          <text class="node-label" :y="SITE_R + 15">{{ item.site.name }}</text>
        </g>

        <!-- Hint -->
        <text v-if="!expandedSiteId" :x="W + vbX - 12" :y="H + vbY - 12"
              style="font-family:var(--font-sans);font-size:11px;fill:var(--fg3);text-anchor:end;pointer-events:none">
          {{ t('topology.expand_hint') }}
        </text>
      </svg>

      <!-- Site gateway hover tooltip -->
      <div v-if="siteTooltip" :style="{
             position: 'absolute',
             left: siteTooltip.x + 'px',
             top: siteTooltip.y + 'px',
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
          {{ siteTooltip.site.name }}
        </div>
        <div style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg2); margin-bottom: 4px">
          {{ siteTooltip.site.cidr }}
        </div>
        <div style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0; border-top: 1px solid var(--border); padding-top: 4px; margin-top: 2px">
          <div style="margin-bottom: 2px">
            <span :style="siteTooltip.site.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                  style="font-size:9px">{{ siteTooltip.site.gatewayOnline ? '●' : '○' }}</span>
            {{ siteTooltip.site.gatewayPeerName }}
            <span style="font-family: var(--font-mono); color: var(--fg2)">{{ siteTooltip.site.gatewayIp }}</span>
          </div>
          <div>{{ siteTooltip.site.gatewayLastSeenAt ? 'Handshake ' + relativeTime(siteTooltip.site.gatewayLastSeenAt) : t('topology.no_handshake') }}</div>
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
          Keine Ports definiert
        </div>
      </div>
    </div>
  `,
  // Expose constants to template via data so Vue can see them.
  created() {
    this.CX = CX; this.CY = CY;
    this.HUB_R = HUB_R; this.SITE_R = SITE_R;
    this.RESOURCE_R = RESOURCE_R; this.LIVE_DOT_R = LIVE_DOT_R;
  },
});
