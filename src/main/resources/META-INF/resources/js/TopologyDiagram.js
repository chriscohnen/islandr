import { defineComponent } from "vue";
import { PATHS as ICON_PATHS } from "/js/Icons.js";

// Two-ring radial topology:
//   Hub in the center (with the permitted ambient pulse, see app.css).
//   Inner ring  = Sites — one node per site, evenly spaced.
//   Outer ring  = Resources — grouped under their parent site, arranged in
//                a small arc around the site's angle so the visual lineage
//                stays obvious (resources of "Hamburg" sit next to the
//                "Hamburg" node, not on the other side of the diagram).
// Plus a small dot cluster right next to the hub for "live peers" (peers
// that handshook in the last 5 minutes). These come and go; everything
// else is stable across refreshes.
//
// All clicks emit one of two events:
//   @site     → payload is the site id; dashboard navigates to /networks/{id}/resources
//   @resource → payload is { siteId, resourceId }; same destination
const W = 720;
const H = 480;
const HUB_X = W / 2;
const HUB_Y = H / 2;
const HUB_R = 30;
const SITE_RING = 130;
const RESOURCE_RING = 220;
const SITE_R = 26;
const RESOURCE_R = 16;
const LIVE_DOT_R = 4;
const LIVE_DOT_ORBIT = 56;  // distance from hub center for the live cluster

// Glyph paths in 24×24 viewport, drawn around (0,0). Stroke-based so they
// pick up the theme.
const GLYPH_SITE = "M-10 -5 h20 v10 h-20 z M-6 0 h12";          // building / office
const GLYPH_RESOURCE = "M-6 -5 h12 v10 h-12 z M-3 -2 h6 M-3 2 h6"; // server / box

function angleAt(index, total, startDeg = -90) {
  // Evenly spaced angles; start at the top (-90°) and go clockwise.
  const start = (startDeg * Math.PI) / 180;
  return start + (2 * Math.PI * index) / Math.max(total, 1);
}

// Compute resource angles: each site occupies an arc whose width grows with
// its resource share. Resources of the same site are evenly distributed
// within that arc, with a small inner gap so resources don't touch the site
// node visually. If there's just one site, resources span the full circle.
function buildResourceLayout(sites, resources) {
  if (sites.length === 0) return [];
  const totalRes = resources.length;
  if (totalRes === 0) return [];
  // Group resources by siteId, preserving the input order so the same
  // resource always lands at the same angle.
  const grouped = {};
  for (const r of resources) {
    (grouped[r.siteId] = grouped[r.siteId] || []).push(r);
  }
  const out = [];
  for (let si = 0; si < sites.length; si++) {
    const site = sites[si];
    const list = grouped[site.id] || [];
    if (list.length === 0) continue;
    const siteAngle = angleAt(si, sites.length);
    // Each resource gets an angular slot equal to (2π / total resources).
    // The site's own resources cluster around the site's angle so the
    // lineage is visually obvious.
    const slot = (2 * Math.PI) / totalRes;
    const arc = slot * list.length;
    const arcStart = siteAngle - arc / 2 + slot / 2;
    for (let ri = 0; ri < list.length; ri++) {
      out.push({
        resource: list[ri],
        siteIndex: si,
        angle: arcStart + ri * slot,
      });
    }
  }
  return out;
}

export default defineComponent({
  name: "TopologyDiagram",
  props: {
    sites: { type: Array, required: true },
    resources: { type: Array, required: true },
    livePeers: { type: Array, default: () => [] },
    resourceOverflow: { type: Number, default: 0 },
    // wgServerEndpoint aus den Settings — Anzeige unter dem Hub-Label.
    // Format ist normalerweise 'host:port' oder 'ip:port'.
    endpoint: { type: String, default: "" },
  },
  emits: ["site", "resource"],
  computed: {
    siteLayout() {
      return this.sites.map((s, i) => {
        const angle = angleAt(i, this.sites.length);
        return {
          site: s,
          angle,
          x: HUB_X + SITE_RING * Math.cos(angle),
          y: HUB_Y + SITE_RING * Math.sin(angle),
        };
      });
    },
    resourceLayout() {
      return buildResourceLayout(this.sites, this.resources).map((entry) => ({
        ...entry,
        x: HUB_X + RESOURCE_RING * Math.cos(entry.angle),
        y: HUB_Y + RESOURCE_RING * Math.sin(entry.angle),
      }));
    },
    livePeerLayout() {
      // Arrange live peers in a tight ring just outside the hub. 8 slots is
      // plenty for the UI; the backend caps at 12 and we show a +N text if
      // there are more. Slots stay deterministic per index so dots don't
      // jump when one comes and goes (the index changes, but at 5-min
      // window the visual churn is acceptable for v1).
      return this.livePeers.slice(0, 8).map((p, i) => {
        const angle = angleAt(i, 8, -135);  // start at upper-left
        return {
          peer: p,
          x: HUB_X + LIVE_DOT_ORBIT * Math.cos(angle),
          y: HUB_Y + LIVE_DOT_ORBIT * Math.sin(angle),
        };
      });
    },
    siteByIndex() {
      return Object.fromEntries(this.sites.map((s, i) => [i, s]));
    },
  },
  methods: {
    glyphSite() { return GLYPH_SITE; },
    // Returns the inline-SVG path markup for the resource type, ready to
    // splat into a <g v-html>. Falls back to the legacy box-glyph when the
    // resource has no type (older rows before V12 default to 'computer').
    resourceIconMarkup(type) {
      const key = type || "computer";
      const paths = ICON_PATHS[key] || ICON_PATHS.computer;
      // Icons are designed for a 24×24 viewBox centred at (12,12). Inside our
      // node we translate to (-12,-12) and scale so the glyph fits the node.
      return paths.join("");
    },
    onSiteClick(siteId) { this.$emit("site", siteId); },
    onResourceClick(siteId, resourceId) {
      this.$emit("resource", { siteId, resourceId });
    },
    relativeTime(iso) {
      if (!iso) return "—";
      const diff = Date.now() - new Date(iso).getTime();
      const s = Math.round(diff / 1000);
      if (s < 60) return "vor " + s + "s";
      const m = Math.round(s / 60);
      return "vor " + m + " min";
    },
    resourceTitle(r) {
      const ports = (r.portLabels && r.portLabels.length > 0)
        ? r.portLabels.join(", ")
        : "keine Ports";
      return r.name + " · " + r.ip + " · " + ports;
    },
  },
  template: `
    <div v-if="sites.length === 0" class="topo-empty">
      <span>Noch keine Standorte angelegt. Lege unter </span><strong>Netzwerke</strong><span> einen Standort mit Ressourcen an, dann erscheinen sie hier.</span>
    </div>
    <svg v-else class="topo" :viewBox="'0 0 ' + ${W} + ' ' + ${H}" role="img" aria-label="Netzwerk-Topologie">
      <!-- Hub-to-Site links (drawn first so nodes paint over them) -->
      <g>
        <line v-for="item in siteLayout" :key="'sl-' + item.site.id"
              class="link"
              :x1="${HUB_X}" :y1="${HUB_Y}"
              :x2="item.x"  :y2="item.y" />
      </g>

      <!-- Site-to-Resource links — short curves so the visual grouping reads -->
      <g>
        <line v-for="item in resourceLayout" :key="'rl-' + item.resource.id"
              class="link"
              :x1="siteLayout[item.siteIndex].x" :y1="siteLayout[item.siteIndex].y"
              :x2="item.x" :y2="item.y" />
      </g>

      <!-- Hub: pulse + solid core. The pulse is CSS-driven and respects
           prefers-reduced-motion. -->
      <g>
        <circle class="hub-pulse" :cx="${HUB_X}" :cy="${HUB_Y}" :r="${HUB_R}" />
        <circle class="hub-core"  :cx="${HUB_X}" :cy="${HUB_Y}" :r="${HUB_R} - 6" />
        <text class="hub-label"   :x="${HUB_X}" :y="${HUB_Y} + ${HUB_R} + 16">Hub</text>
        <text v-if="endpoint" class="hub-endpoint"
              :x="${HUB_X}" :y="${HUB_Y} + ${HUB_R} + 30">{{ endpoint }}</text>
      </g>

      <!-- Live-peer dots: small accent-coloured circles right outside the hub.
           No labels (too many overlapping at high concurrency); the tooltip
           carries name+ip+lastSeen. -->
      <g>
        <circle v-for="(d, i) in livePeerLayout" :key="'lp-' + d.peer.id"
                :cx="d.x" :cy="d.y" :r="${LIVE_DOT_R}"
                class="hub-core" style="opacity: 0.85">
          <title>{{ d.peer.name }} · {{ d.peer.assignedIp }} · {{ relativeTime(d.peer.lastSeenAt) }}</title>
        </circle>
        <text v-if="livePeers.length > 8" class="hub-label"
              :x="${HUB_X}" :y="${HUB_Y} - ${HUB_R} - 12">
          + {{ livePeers.length - 8 }} weitere live
        </text>
      </g>

      <!-- Resources (outer ring, drawn before sites so sites sit on top
           visually when a label would otherwise overlap a resource glyph) -->
      <g v-for="item in resourceLayout" :key="item.resource.id"
         class="node live"
         @click="onResourceClick(item.resource.siteId, item.resource.id)"
         :transform="'translate(' + item.x + ',' + item.y + ')'">
        <title>{{ resourceTitle(item.resource) }}</title>
        <circle class="node-ring" :r="${RESOURCE_R}" />
        <circle class="node-bg"   :r="${RESOURCE_R} - 2" />
        <!-- Type-Icon: 24x24-Lucide-Pfade. scale(0.8) macht die Glyphe ~19px,
             passt knapp in den 16px-Knoten. translate verschiebt das (0,0)
             des Glyph-Mittelpunkts (12,12 in viewBox) zur Knoten-Mitte. -->
        <g class="node-icon"
           transform="translate(-9.6,-9.6) scale(0.8)"
           fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round"
           v-html="resourceIconMarkup(item.resource.type)" />
        <text   class="node-label" :y="${RESOURCE_R} + 12">{{ item.resource.name }}</text>
      </g>

      <!-- Sites (inner ring) -->
      <g v-for="item in siteLayout" :key="item.site.id"
         class="node live"
         @click="onSiteClick(item.site.id)"
         :transform="'translate(' + item.x + ',' + item.y + ')'">
        <title>{{ item.site.name }} · {{ item.site.cidr }} · {{ item.site.resourceCount }} Ressource{{ item.site.resourceCount === 1 ? '' : 'n' }}</title>
        <circle class="node-ring" :r="${SITE_R}" />
        <circle class="node-bg"   :r="${SITE_R} - 2" />
        <path   class="node-glyph" :d="glyphSite()" transform="scale(0.8)" />
        <text   class="node-label" :y="${SITE_R} + 14">{{ item.site.name }}</text>
      </g>

      <!-- Resource overflow indicator -->
      <text v-if="resourceOverflow > 0" class="hub-label"
            :x="${W} - 12" :y="${H} - 12" style="text-anchor: end">
        + {{ resourceOverflow }} Ressourcen nicht gezeigt
      </text>
    </svg>
  `,
});
