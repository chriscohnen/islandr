import { defineComponent } from "vue";
import { t, relativeTime } from "/js/i18n.js";

// World-map topology view (ADR-0021, issue #11): an alternative to the radial
// TopologyDiagram for operators with geographically distributed sites. Manual
// geocoding only — no GeoIP, see ADR-0021's Decision section for why. Land
// outlines are a bundled static asset (no CDN tile server — air-gapped
// deployments must render this with no network access at all), projected
// with a hand-rolled equirectangular formula rather than a d3-geo dependency
// (ADR-0002: no npm build step, minimal dependency surface).
//
// x = (lng + 180) * (W / 360)
// y = (90 - lat) * (H / 180)
const W = 960;
const H = 500;
// The full equirectangular frame includes empty polar regions no site will
// ever be in. Crop the visible viewBox to a band that still covers the
// inhabited world (northern Scandinavia down through southern South
// America/Australia) instead of wasting space on Antarctica/the high Arctic.
const VIEW_Y_MIN = 10;
const VIEW_Y_MAX = 420;
const HUB_R = 7;
const SITE_R = 5;

let landDataPromise = null;
// Module-level cache: the land outline file never changes at runtime, so a
// second mount (switching dashboard tabs back and forth) shouldn't refetch it.
function loadLandData() {
  if (!landDataPromise) {
    landDataPromise = fetch("/data/world-land-110m.json").then((res) => {
      if (!res.ok) throw new Error("HTTP " + res.status);
      return res.json();
    });
  }
  return landDataPromise;
}

function project(lat, lng) {
  return {
    x: (lng + 180) * (W / 360),
    y: (90 - lat) * (H / 180),
  };
}

export default defineComponent({
  name: "TopologyWorldMap",
  props: {
    sites: { type: Array, required: true }, // DashboardDto.TopologySite[]
    hubLat: { type: Number, default: null },
    hubLon: { type: Number, default: null },
    hubLabel: { type: String, default: "" },
  },
  emits: ["site"],
  data() {
    return {
      land: null,
      loading: true,
      error: null,
      tooltip: null, // { site, x, y }
    };
  },
  async mounted() {
    try {
      this.land = await loadLandData();
    } catch (e) {
      this.error = t("dashboard.worldmap_load_error", { error: e.message });
    } finally {
      this.loading = false;
    }
  },
  computed: {
    hasHub() {
      return this.hubLat != null && this.hubLon != null;
    },
    hubPoint() {
      return this.hasHub ? project(this.hubLat, this.hubLon) : null;
    },
    geocodedSites() {
      return this.sites.filter((s) => s.gatewayLat != null && s.gatewayLng != null);
    },
    sitePoints() {
      return this.geocodedSites.map((s) => ({ site: s, ...project(s.gatewayLat, s.gatewayLng) }));
    },
    // One combined path across every land polygon/ring — far fewer DOM nodes
    // than one <path> per country, and there's only ever one fill/stroke style.
    landPath() {
      if (!this.land) return "";
      let d = "";
      for (const polygon of this.land.polygons) {
        for (const ring of polygon) {
          if (ring.length === 0) continue;
          const pts = ring.map(([lng, lat]) => project(lat, lng));
          d += "M" + pts.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join("L") + "Z";
        }
      }
      return d;
    },
    viewBox() {
      return `0 ${VIEW_Y_MIN} ${W} ${VIEW_Y_MAX - VIEW_Y_MIN}`;
    },
  },
  methods: {
    t,
    relativeTime(iso) { return relativeTime(iso); },
    // Quadratic bezier control point offset perpendicular to the hub-site
    // line, matching the design brief's "slightly curved" connection lines.
    linkPath(pt) {
      if (!this.hubPoint) return "";
      const x1 = this.hubPoint.x, y1 = this.hubPoint.y;
      const x2 = pt.x, y2 = pt.y;
      const dx = x2 - x1, dy = y2 - y1;
      const dist = Math.hypot(dx, dy) || 1;
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
      const cx = mx - (dy / dist) * dist * 0.15;
      const cy = my + (dx / dist) * dist * 0.15;
      return `M${x1},${y1} Q${cx},${cy} ${x2},${y2}`;
    },
    linkClass(pt) {
      return pt.site.gatewayOnline ? "worldmap-link worldmap-link-live" : "worldmap-link worldmap-link-down";
    },
    onSiteClick(site) {
      this.$emit("site", site.id);
    },
    showTooltip(event, site) {
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { site, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    moveTooltip(event) {
      if (!this.tooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { ...this.tooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hideTooltip() { this.tooltip = null; },
  },
  template: `
    <div style="position: relative">
      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <div v-else-if="!hasHub || geocodedSites.length < 1" class="muted">{{ t('dashboard.worldmap_empty') }}</div>
      <svg v-else class="topo worldmap" :viewBox="viewBox" role="img" :aria-label="t('dashboard.worldmap_title')">
        <rect class="worldmap-ocean" x="0" :y="0" :width="W" :height="H" />
        <path class="worldmap-land" :d="landPath" />

        <path v-for="pt in sitePoints" :key="'l-'+pt.site.id" :class="linkClass(pt)" :d="linkPath(pt)" />

        <g v-if="hasHub">
          <circle class="hub-pulse" :cx="hubPoint.x" :cy="hubPoint.y" :r="HUB_R" />
          <circle class="hub-core"  :cx="hubPoint.x" :cy="hubPoint.y" :r="HUB_R - 3" />
          <text class="hub-label" :x="hubPoint.x" :y="hubPoint.y + HUB_R + 12">{{ hubLabel || t('dashboard.worldmap_hub') }}</text>
        </g>

        <g v-for="pt in sitePoints" :key="pt.site.id"
           class="node live" style="cursor:pointer"
           @click="onSiteClick(pt.site)"
           @mouseenter="showTooltip($event, pt.site)"
           @mousemove="moveTooltip($event)"
           @mouseleave="hideTooltip"
           :transform="'translate('+pt.x+','+pt.y+')'">
          <circle class="node-ring worldmap-pin" :r="SITE_R" :style="pt.site.gatewayOnline ? 'stroke: var(--status-ok); stroke-width: 2' : 'stroke: var(--fg3); stroke-width: 1.6'" />
          <circle class="node-bg" :r="SITE_R - 2" />
          <text class="node-label" :y="SITE_R + 12">{{ pt.site.name }}</text>
        </g>
      </svg>

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
          {{ tooltip.site.name }}
        </div>
        <div style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
          <div style="margin-bottom: 2px">
            <span :style="tooltip.site.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                  style="font-size:9px">{{ tooltip.site.gatewayOnline ? '●' : '○' }}</span>
            <span style="font-family: var(--font-mono); color: var(--fg2)">{{ tooltip.site.gatewayIp }}</span>
          </div>
          <div>{{ tooltip.site.gatewayLastSeenAt ? t('topology.handshake', { when: relativeTime(tooltip.site.gatewayLastSeenAt) }) : t('topology.no_handshake') }}</div>
        </div>
      </div>
    </div>
  `,
  created() {
    this.W = W; this.H = H; this.HUB_R = HUB_R; this.SITE_R = SITE_R;
  },
});
