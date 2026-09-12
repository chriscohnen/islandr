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
// Link paths live in map user-space (unlike pins/labels, which counter-scale
// via an inner scale(1/zoom) group — see the template comment above the hub
// <g>) so their geometry can follow the projected hub/site coordinates.
// That means a constant stroke-width in user units covers more screen
// pixels as zoom increases (the same user-space width maps to a shrinking
// viewBox), so lines look ever thicker while zooming in unless the
// stroke-width itself is scaled down by 1/zoom to compensate.
const LINK_STROKE_WIDTH = 1.4;
// Traffic-tier thickness (issue #34, extended from TopologyDiagram.js to the
// geo map): a gateway whose live-poll byte counters are moving gets a
// visibly thicker line than a merely-connected-but-idle one — richer signal
// than the flat online/offline color+dash split alone, and it's the delta
// in *this*, not a binary state, that actually varies interestingly poll to
// poll.
const LINK_STROKE_WIDTH_FLOWING = 2.2;
const LINK_STROKE_WIDTH_HEAVY = 3.4;
// Same reasoning applies to the dashed "down" link's dash pattern (see
// .worldmap-link-down in app.css, which this JS-computed value overrides
// inline): a fixed dasharray in user units stretches into ever-longer
// dashes/gaps while zooming in, until it stops reading as a dashed line and
// looks like disconnected segments instead.
const LINK_DASH = 3;
const LINK_GAP = 4;
// A gateway peer often routes more than one network — all of them share that
// peer's coordinates, so one pin per site stacked identical labels on top of
// each other into an unreadable smear. Instead: one pin per gateway peer, and
// its networks render as a small grid of squares under the label, one square
// per network — a compact "how many, at a glance" indicator instead of N
// overlapping name labels. Capped at a 6×4 grid (24); beyond that a single
// filled block stands in for "too many to enumerate individually here".
const GRID_COLS = 6;
const GRID_ROWS = 4;
const GRID_MAX = GRID_COLS * GRID_ROWS;
const GRID_CELL = 7;
const GRID_GAP = 2;

// Zoom/pan (issue: several sites close together, e.g. all in Germany, are
// indistinguishable at whole-world scale). SVG-viewBox zoom, same technique
// TopologyDiagram.js already uses for its own drag-to-pan — a smaller
// viewBox = zoomed in, panned by moving the viewBox's center. Wheel zoom is
// anchored on the cursor (the point under the pointer stays put), matching
// how every map app already behaves; zoom buttons anchor on the current
// center for the no-mouse-wheel case (touch, trackpad without a wheel).
const BASE_W = W;
const BASE_H = VIEW_Y_MAX - VIEW_Y_MIN;
const MID_Y = (VIEW_Y_MIN + VIEW_Y_MAX) / 2;
const MIN_ZOOM = 1;
const MAX_ZOOM = 64;
const WHEEL_ZOOM_STEP = 1.15;
const BUTTON_ZOOM_STEP = 1.6;
const DRAG_THRESHOLD_PX = 4;

function clamp(v, lo, hi) {
  return Math.min(Math.max(v, lo), hi);
}

// Zoom/pan is remembered across mounts (leaving the dashboard route and
// coming back, or a full page reload) via localStorage — same convention as
// islandr.theme/islandr.locale. It's a single global entry, not keyed per
// hub: this is a single-tenant dashboard, one map, so there's nothing to
// disambiguate.
const VIEW_STORAGE_KEY = "islandr.worldmap.view";
const SAVE_VIEW_DEBOUNCE_MS = 300;

function loadSavedView() {
  try {
    const v = JSON.parse(localStorage.getItem(VIEW_STORAGE_KEY));
    if (v && Number.isFinite(v.zoom) && Number.isFinite(v.panX) && Number.isFinite(v.panY)) {
      return v;
    }
  } catch { /* missing, corrupt, or storage unavailable — fall back to defaults */ }
  return null;
}

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

// A single out-of-range coordinate (bad manual entry, or — as happened before
// the hub form got its own paste-splitting fix — a paste silently truncated
// into a garbage value) must never be allowed to project to a huge x/y and
// draw a line clear across the map. Reject at the source instead of trusting
// every stored lat/lng to already be sane.
function isValidCoord(lat, lng) {
  return typeof lat === "number" && typeof lng === "number"
    && Number.isFinite(lat) && Number.isFinite(lng)
    && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
}

export default defineComponent({
  name: "TopologyWorldMap",
  props: {
    sites: { type: Array, required: true }, // DashboardDto.TopologySite[]
    livePeers: { type: Array, default: () => [] }, // /api/v1/peers/live, tagged with trafficTier by DashboardView
    hubLat: { type: Number, default: null },
    hubLon: { type: Number, default: null },
    hubLabel: { type: String, default: "" },
    // Portal (self-service) reuse of this component (#43) — see the same
    // prop on TopologyDiagram.js for why: swaps Admin-worded tooltip
    // fallback text, doesn't change what data is shown (the backend
    // already omits gatewayIp/gatewayLastSeenAt for portal callers).
    portal: { type: Boolean, default: false },
  },
  emits: ["site"],
  data() {
    const saved = loadSavedView();
    return {
      land: null,
      loading: true,
      error: null,
      tooltip: null, // { site, x, y }
      zoom: saved ? clamp(saved.zoom, MIN_ZOOM, MAX_ZOOM) : 1,
      panX: saved ? saved.panX : 0,
      panY: saved ? saved.panY : 0,
      dragging: false,
      dragStartPointer: null,
      dragStartPan: null,
      _pendingPointerId: null,
      _pendingTarget: null,
      _saveViewTimer: null,
    };
  },
  watch: {
    zoom() { this.scheduleSaveView(); },
    panX() { this.scheduleSaveView(); },
    panY() { this.scheduleSaveView(); },
  },
  beforeUnmount() {
    clearTimeout(this._saveViewTimer);
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
      return isValidCoord(this.hubLat, this.hubLon);
    },
    hubPoint() {
      return this.hasHub ? project(this.hubLat, this.hubLon) : null;
    },
    geocodedSites() {
      return this.sites.filter((s) => isValidCoord(s.gatewayLat, s.gatewayLng));
    },
    // One entry per gateway peer, carrying every network (site) it routes —
    // see the GRID_* comment above for why sites no longer get one pin each.
    gatewayGroups() {
      const map = new Map();
      for (const s of this.geocodedSites) {
        if (!map.has(s.gatewayPeerId)) {
          map.set(s.gatewayPeerId, {
            gatewayPeerId: s.gatewayPeerId,
            gatewayPeerName: s.gatewayPeerName,
            gatewayOnline: s.gatewayOnline,
            gatewayIp: s.gatewayIp,
            gatewayLastSeenAt: s.gatewayLastSeenAt,
            gatewayLat: s.gatewayLat,
            gatewayLng: s.gatewayLng,
            sites: [],
          });
        }
        map.get(s.gatewayPeerId).sites.push(s);
      }
      return Array.from(map.values());
    },
    // A homelab commonly has the hub and its one site-peer at the very same
    // physical address — geocoded to identical (or near-identical)
    // coordinates. Projected as-is, the hub's pin+label and that gateway's
    // pin+label land exactly on top of each other, garbling both labels into
    // unreadable overlapping text. Nudge a gateway pin outward when it's
    // essentially at the same physical address as the hub — separates the
    // two without needing the operator to fake slightly-different
    // coordinates for what is genuinely one location.
    //
    // The collision check itself must run in actual lat/lng degrees, NOT in
    // projected pixels: the whole-world equirectangular projection is only
    // ~2.7px per degree, so a pixel-space threshold meant to mean "same
    // address" instead covers several hundred kilometers — any second site
    // whose gateway merely happened to be in the same *region* as the hub
    // (e.g. Munich, ~300km from a Frankfurt hub) falsely triggered it and
    // got shoved by the fixed on-screen NUDGE_PX offset, which at this
    // projection scale corresponds to hundreds more km in a fixed
    // direction — moving the pin into a different country entirely
    // (reported 2026-07-29: a Munich site landed in the Balkans). Comparing
    // degrees directly keeps the threshold meaning what it says regardless
    // of the projection's px/degree scale.
    // Two gateways can just as easily share the same physical address as a
    // gateway and the hub — two routers in the same office is not a
    // hypothetical, it is exactly the "office-router"/second-router case
    // this file already handles for hub-vs-gateway. Cluster gateways whose
    // coordinates collide with each other (same COLLISION_DEG threshold as
    // the hub check below) and fan each cluster's members out evenly around
    // their shared point, instead of stacking pin, label, and per-network
    // grid exactly on top of one another.
    gatewayPoints() {
      const COLLISION_DEG = 0.05; // ~5 km — same building/campus, not just "nearby"
      const NUDGE_PX = 18;
      const FAN_RADIUS_PX = 22;

      const groups = this.gatewayGroups;
      const clusters = [];
      const assigned = new Array(groups.length).fill(false);
      for (let i = 0; i < groups.length; i++) {
        if (assigned[i]) continue;
        const cluster = [groups[i]];
        assigned[i] = true;
        for (let j = i + 1; j < groups.length; j++) {
          if (assigned[j]) continue;
          const dLat = groups[j].gatewayLat - groups[i].gatewayLat;
          const dLng = groups[j].gatewayLng - groups[i].gatewayLng;
          if (Math.hypot(dLat, dLng) < COLLISION_DEG) {
            cluster.push(groups[j]);
            assigned[j] = true;
          }
        }
        clusters.push(cluster);
      }

      const out = [];
      for (const cluster of clusters) {
        const g0 = cluster[0];
        let base = project(g0.gatewayLat, g0.gatewayLng);
        if (this.hasHub) {
          const dLat = g0.gatewayLat - this.hubLat, dLng = g0.gatewayLng - this.hubLon;
          if (Math.hypot(dLat, dLng) < COLLISION_DEG) {
            base = { x: base.x + NUDGE_PX, y: base.y + NUDGE_PX * 0.6 };
          }
        }
        if (cluster.length === 1) {
          out.push({ gateway: cluster[0], ...base });
          continue;
        }
        cluster.forEach((g, idx) => {
          const angle = (2 * Math.PI * idx) / cluster.length - Math.PI / 2;
          out.push({
            gateway: g,
            x: base.x + FAN_RADIUS_PX * Math.cos(angle),
            y: base.y + FAN_RADIUS_PX * Math.sin(angle),
          });
        });
      }
      return out;
    },
    // One <path> per land polygon (~124), not one giant combined path across
    // all of them. Was combined originally for fewer DOM nodes, but 124
    // <path> elements is still cheap and this way one polygon's markup is
    // isolated for debugging. (An earlier commit attributed the "line
    // between two unrelated landmasses" defect (2026-07-28) to this — a
    // suspected cross-subpath tessellator artifact from the combined path.
    // That was a misdiagnosis: the real cause was the antimeridian-seam
    // handling inside a single ring, see the comment in the loop below.)
    landPaths() {
      if (!this.land) return [];
      return this.land.polygons.map((polygon) => {
        let d = "";
        for (const ring of polygon) {
          if (ring.length === 0) continue;
          // A handful of rings (e.g. Fiji, the Eurasia landmass at Chukotka,
          // Antarctica) cross the ±180° antimeridian — consecutive points
          // jump from ~+180 to ~-180 (or vice versa), because +180 and -180
          // are geographically adjacent but land at opposite ends of the
          // flat projection.
          //
          // An earlier version of this code "fixed" that by breaking into a
          // new subpath (M instead of L) at each such jump. That doesn't
          // work: SVG auto-closes every unclosed subpath straight back to
          // its own first point when filling — with or without an explicit
          // Z — so each fragment between two seam crossings becomes its own
          // closed shape. For a ring where the seam crossing sits in the
          // middle of one landmass (e.g. Eurasia's ring runs most of the way
          // from Africa to Siberia before it ever touches the seam), that
          // auto-close draws a straight chord from Siberia back to Africa —
          // a diagonal line clear across the map — which then self-
          // intersects the rest of the ring and flips the nonzero fill-rule
          // winding in the overlap. That's the "triangle that inverts the
          // map" defect (2026-07-28): not a data problem, and not fixable by
          // isolating polygons into separate <path> elements, because the
          // corruption happens within a single ring/subpath.
          //
          // The actual fix: never re-normalize longitude into a hard
          // ±180 range at all. Track a continuously "unwrapped" longitude
          // per ring — each step adds the shortest delta to the previous
          // point (which is always < 180° since source points are dense),
          // so a brief antimeridian excursion just projects to x slightly
          // outside [0, W] instead of snapping to the opposite edge. The
          // ring then stays a single, uninterrupted, self-consistent
          // subpath with no artificial seam and nothing to self-intersect.
          // The bit of geometry outside the visible viewBox is simply
          // clipped by the <svg>, same as any other off-canvas content.
          let unwrappedLng = null;
          ring.forEach(([lng, lat], i) => {
            if (unwrappedLng === null) {
              unwrappedLng = lng;
            } else {
              const delta = (((lng - unwrappedLng + 180) % 360 + 360) % 360) - 180;
              unwrappedLng += delta;
            }
            const p = project(lat, unwrappedLng);
            d += (i === 0 ? "M" : "L") + `${p.x.toFixed(1)},${p.y.toFixed(1)}`;
          });
          d += "Z";
        }
        return d;
      });
    },
    // Zoomed/panned viewBox. At zoom=1 this reduces to the original static
    // `0 VIEW_Y_MIN W (VIEW_Y_MAX-VIEW_Y_MIN)` box — rangeX/rangeY are 0 there,
    // clamping pan to exactly 0, so nothing changes for the common unzoomed case.
    viewBoxRect() {
      const w = BASE_W / this.zoom;
      const h = BASE_H / this.zoom;
      const rangeX = (BASE_W - w) / 2;
      const rangeY = (BASE_H - h) / 2;
      const panX = clamp(this.panX, -rangeX, rangeX);
      const panY = clamp(this.panY, -rangeY, rangeY);
      return { x: W / 2 - w / 2 + panX, y: MID_Y - h / 2 + panY, w, h };
    },
    viewBox() {
      const b = this.viewBoxRect;
      return `${b.x} ${b.y} ${b.w} ${b.h}`;
    },
    canZoomIn() { return this.zoom < MAX_ZOOM; },
    canZoomOut() { return this.zoom > MIN_ZOOM; },
    // Live peer's own `id` is the same Islandr peer-id as a site's
    // `gatewayPeerId` when that peer is the site's router — direct join, no
    // publicKey round-trip needed.
    livePeerByGatewayId() {
      const map = new Map();
      for (const p of this.livePeers) if (p.id) map.set(p.id, p);
      return map;
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
    trafficTierForGateway(gatewayPeerId) {
      const peer = this.livePeerByGatewayId.get(gatewayPeerId);
      return peer ? peer.trafficTier || "idle" : "idle";
    },
    trafficTier(pt) {
      return this.trafficTierForGateway(pt.gateway.gatewayPeerId);
    },
    trafficLabelKey(gateway) {
      const tier = this.trafficTierForGateway(gateway.gatewayPeerId);
      return "topology.traffic_" + (tier === "flowing-heavy" ? "flowing_heavy" : tier);
    },
    linkClass(pt) {
      const base = pt.gateway.gatewayOnline ? "worldmap-link worldmap-link-live" : "worldmap-link worldmap-link-down";
      const tier = this.trafficTier(pt);
      if (!pt.gateway.gatewayOnline || tier === "idle") return base;
      return base + (tier === "flowing-heavy" ? " worldmap-link-flowing-heavy" : " worldmap-link-flowing");
    },
    linkStyle(pt) {
      const tier = pt.gateway.gatewayOnline ? this.trafficTier(pt) : "idle";
      const width = tier === "flowing-heavy" ? LINK_STROKE_WIDTH_HEAVY
                  : tier === "flowing"       ? LINK_STROKE_WIDTH_FLOWING
                  : LINK_STROKE_WIDTH;
      const style = { strokeWidth: (width / this.zoom).toFixed(2) };
      if (!pt.gateway.gatewayOnline) {
        style.strokeDasharray = `${(LINK_DASH / this.zoom).toFixed(2)} ${(LINK_GAP / this.zoom).toFixed(2)}`;
      }
      return style;
    },
    isMulti(group) {
      return group.sites.length > 1;
    },
    gridOverflow(group) {
      return group.sites.length > GRID_MAX;
    },
    // Square positions for a gateway's network grid, relative to the pin —
    // one square per network, row-major, capped at GRID_MAX (see comment at
    // the top of the file). Directly under the pin — the peer-name label
    // sits beside the pin now (labelDir), not below, so there's no text to
    // clear here anymore.
    gridCells(group) {
      const n = Math.min(group.sites.length, GRID_MAX);
      const gridW = GRID_COLS * GRID_CELL + (GRID_COLS - 1) * GRID_GAP;
      const startX = -gridW / 2;
      const startY = SITE_R + 8;
      const cells = [];
      for (let i = 0; i < n; i++) {
        cells.push({
          site: group.sites[i],
          x: startX + (i % GRID_COLS) * (GRID_CELL + GRID_GAP),
          y: startY + Math.floor(i / GRID_COLS) * (GRID_CELL + GRID_GAP),
        });
      }
      return cells;
    },
    gridBBox() {
      const gridW = GRID_COLS * GRID_CELL + (GRID_COLS - 1) * GRID_GAP;
      const gridH = GRID_ROWS * GRID_CELL + (GRID_ROWS - 1) * GRID_GAP;
      return { x: -gridW / 2, y: SITE_R + 8, w: gridW, h: gridH };
    },
    onSiteClick(site) {
      this.$emit("site", site.id);
    },
    onGatewayClick(group) {
      // A lone-network gateway is itself the click target (matches the old
      // one-pin-per-site behavior); a multi-network gateway has no single
      // target — only its individual grid squares are clickable.
      if (!this.isMulti(group)) this.onSiteClick(group.sites[0]);
    },
    showGatewayTooltip(event, group) {
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { gateway: group, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    showSiteTooltip(event, site) {
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { site, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    moveTooltip(event) {
      if (!this.tooltip) return;
      const rect = this.$el.getBoundingClientRect();
      this.tooltip = { ...this.tooltip, x: event.clientX - rect.left + 14, y: event.clientY - rect.top - 10 };
    },
    hideTooltip() { this.tooltip = null; },
    // Debounced so a wheel-zoom flurry or an in-progress drag (both fire
    // this on every step) don't hammer localStorage — only the settled
    // value after a short pause gets written.
    scheduleSaveView() {
      clearTimeout(this._saveViewTimer);
      this._saveViewTimer = setTimeout(() => {
        try {
          localStorage.setItem(VIEW_STORAGE_KEY, JSON.stringify({ zoom: this.zoom, panX: this.panX, panY: this.panY }));
        } catch { /* storage unavailable/full — remembering the view is non-critical */ }
      }, SAVE_VIEW_DEBOUNCE_MS);
    },
    // Labels sit beside a pin (left or right), not centered below it — below
    // collided with the per-network grid and, for pins close together,
    // overlapped the neighboring pin's own label. Side placed away from the
    // map's own center (W/2) so labels tend to point outward, not back
    // toward whatever else is clustered near the middle of the visible world.
    labelDir(x) {
      return x < W / 2 ? 1 : -1;
    },

    // Changes zoom by `factor`, keeping (anchorUserX, anchorUserY) — a point
    // in SVG user-space — fixed on screen. Buttons anchor on the current
    // viewBox center; the wheel handler anchors on the cursor.
    applyZoom(factor, anchorUserX, anchorUserY) {
      const newZoom = clamp(this.zoom * factor, MIN_ZOOM, MAX_ZOOM);
      if (newZoom === this.zoom) return;
      const b = this.viewBoxRect;
      const fracX = (anchorUserX - b.x) / b.w;
      const fracY = (anchorUserY - b.y) / b.h;
      const newW = BASE_W / newZoom;
      const newH = BASE_H / newZoom;
      const newX = anchorUserX - fracX * newW;
      const newY = anchorUserY - fracY * newH;
      this.zoom = newZoom;
      // viewBoxRect derives x/y from panX/panY as `center - half + pan` —
      // invert that to land exactly on newX/newY (then its own clamp keeps
      // it in bounds, which also cleanly handles hitting MIN_ZOOM/MAX_ZOOM).
      this.panX = newX - (W / 2 - newW / 2);
      this.panY = newY - (MID_Y - newH / 2);
    },
    zoomInBtn() {
      const b = this.viewBoxRect;
      this.applyZoom(BUTTON_ZOOM_STEP, b.x + b.w / 2, b.y + b.h / 2);
    },
    zoomOutBtn() {
      const b = this.viewBoxRect;
      this.applyZoom(1 / BUTTON_ZOOM_STEP, b.x + b.w / 2, b.y + b.h / 2);
    },
    resetView() {
      this.zoom = 1;
      this.panX = 0;
      this.panY = 0;
    },
    onWheel(event) {
      event.preventDefault();
      const rect = event.currentTarget.getBoundingClientRect();
      const b = this.viewBoxRect;
      const scale = Math.min(rect.width / b.w, rect.height / b.h);
      if (!scale) return;
      const anchorUserX = b.x + (event.clientX - rect.left) / scale;
      const anchorUserY = b.y + (event.clientY - rect.top) / scale;
      this.applyZoom(event.deltaY < 0 ? WHEEL_ZOOM_STEP : 1 / WHEEL_ZOOM_STEP, anchorUserX, anchorUserY);
    },
    // Drag-to-pan, mirroring TopologyDiagram.js's own pointer-capture pattern:
    // capture is deferred until real movement crosses DRAG_THRESHOLD_PX, so a
    // plain click on a pin still reaches it instead of being swallowed by
    // pointer capture on the svg itself.
    onPointerDown(event) {
      if (this.zoom <= MIN_ZOOM) return;
      this.dragStartPointer = { x: event.clientX, y: event.clientY };
      this.dragStartPan = { x: this.panX, y: this.panY };
      this._pendingPointerId = event.pointerId;
      this._pendingTarget = event.currentTarget;
    },
    onPointerMove(event) {
      if (!this.dragStartPointer) return;
      if (!this.dragging) {
        const moved = Math.hypot(event.clientX - this.dragStartPointer.x, event.clientY - this.dragStartPointer.y);
        if (moved < DRAG_THRESHOLD_PX) return;
        this.dragging = true;
        this._pendingTarget.setPointerCapture(this._pendingPointerId);
      }
      const rect = event.currentTarget.getBoundingClientRect();
      const b = this.viewBoxRect;
      const scale = Math.min(rect.width / b.w, rect.height / b.h);
      if (!scale) return;
      const dxUser = (event.clientX - this.dragStartPointer.x) / scale;
      const dyUser = (event.clientY - this.dragStartPointer.y) / scale;
      this.panX = this.dragStartPan.x - dxUser;
      this.panY = this.dragStartPan.y - dyUser;
    },
    onPointerUp(event) {
      if (this.dragging) {
        try { event.currentTarget.releasePointerCapture(event.pointerId); } catch { /* already released */ }
      }
      this.dragging = false;
      this.dragStartPointer = null;
      this.dragStartPan = null;
      this._pendingPointerId = null;
      this._pendingTarget = null;
    },
  },
  template: `
    <div style="position: relative">
      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <div v-else-if="!hasHub || geocodedSites.length < 1" class="muted">{{ t('dashboard.worldmap_empty') }}</div>
      <svg v-else class="topo worldmap" :viewBox="viewBox" role="img" :aria-label="t('dashboard.worldmap_title')"
           :style="{
             cursor: zoom > 1 ? (dragging ? 'grabbing' : 'grab') : 'default',
             touchAction: zoom > 1 ? 'none' : 'auto',
             userSelect: 'none',
           }"
           @wheel="onWheel"
           @pointerdown="onPointerDown"
           @pointermove="onPointerMove"
           @pointerup="onPointerUp"
           @pointercancel="onPointerUp">
        <rect class="worldmap-ocean" x="0" :y="0" :width="W" :height="H" />
        <path v-for="(d, i) in landPaths" :key="'land-'+i" class="worldmap-land" :d="d" />

        <path v-for="pt in gatewayPoints" :key="'l-'+pt.gateway.gatewayPeerId" :class="linkClass(pt)" :d="linkPath(pt)" :style="linkStyle(pt)" />

        <!-- Pins, labels and the network grid are wrapped in an inner
             scale(1/zoom) — the outer translate positions them at the
             correct geographic point (which must move with the zoomed
             viewBox), but their own size/font stays a constant number of
             screen pixels instead of visually ballooning as the viewBox
             shrinks. Only the land fill and the link lines are meant to
             scale with zoom; markers and text are not. -->
        <g v-if="hasHub" :transform="'translate('+hubPoint.x+','+hubPoint.y+')'">
          <g :transform="'scale('+(1/zoom)+')'">
            <circle class="hub-pulse" cx="0" cy="0" :r="HUB_R" />
            <circle class="hub-core"  cx="0" cy="0" :r="HUB_R - 3" />
            <text class="hub-label" :x="labelDir(hubPoint.x) * (HUB_R + 6)" y="4"
                  :style="'text-anchor:' + (labelDir(hubPoint.x) > 0 ? 'start' : 'end')">{{ hubLabel || t('dashboard.worldmap_hub') }}</text>
          </g>
        </g>

        <g v-for="pt in gatewayPoints" :key="pt.gateway.gatewayPeerId"
           class="node live" :style="isMulti(pt.gateway) ? '' : 'cursor:pointer'"
           :transform="'translate('+pt.x+','+pt.y+')'">
         <g :transform="'scale('+(1/zoom)+')'">
          <circle class="node-ring worldmap-pin" :r="SITE_R" :style="pt.gateway.gatewayOnline ? 'stroke: var(--status-ok); stroke-width: 2' : 'stroke: var(--fg3); stroke-width: 1.6'"
                  :cursor="isMulti(pt.gateway) ? 'default' : 'pointer'"
                  @click="onGatewayClick(pt.gateway)"
                  @mouseenter="showGatewayTooltip($event, pt.gateway)"
                  @mousemove="moveTooltip($event)"
                  @mouseleave="hideTooltip" />
          <circle class="node-bg" :r="SITE_R - 2" style="pointer-events:none" />
          <text class="node-label" :x="labelDir(pt.x) * (SITE_R + 6)" y="4"
                :style="'text-anchor:' + (labelDir(pt.x) > 0 ? 'start' : 'end') + ';pointer-events:none'">{{ pt.gateway.gatewayPeerName }}</text>

          <!-- Per-network grid (only when a gateway routes more than one
               network — see GRID_* comment at the top of the file). A single
               square per network, up to 24; past that, one filled block. -->
          <template v-if="isMulti(pt.gateway)">
            <rect v-if="gridOverflow(pt.gateway)"
                  class="worldmap-net-square"
                  :x="gridBBox().x" :y="gridBBox().y" :width="gridBBox().w" :height="gridBBox().h" rx="3"
                  style="cursor:default" />
            <text v-if="gridOverflow(pt.gateway)" class="worldmap-net-count"
                  :x="0" :y="gridBBox().y + gridBBox().h / 2">{{ pt.gateway.sites.length }}</text>
            <rect v-else v-for="cell in gridCells(pt.gateway)" :key="cell.site.id"
                  class="worldmap-net-square"
                  :x="cell.x" :y="cell.y" :width="GRID_CELL" :height="GRID_CELL" rx="2"
                  style="cursor:pointer"
                  @click="onSiteClick(cell.site)"
                  @mouseenter="showSiteTooltip($event, cell.site)"
                  @mousemove="moveTooltip($event)"
                  @mouseleave="hideTooltip">
              <title>{{ cell.site.name }}</title>
            </rect>
          </template>
         </g>
        </g>
      </svg>

      <div style="position: absolute; right: 10px; bottom: 10px; display: flex; flex-direction: column; gap: 2px">
        <button type="button" class="btn btn-ghost btn-sm" :disabled="!canZoomIn" @click="zoomInBtn"
                :title="t('dashboard.worldmap_zoom_in')" style="width: 28px; height: 28px; padding: 0; font-size: var(--text-md)">+</button>
        <button type="button" class="btn btn-ghost btn-sm" :disabled="!canZoomOut" @click="zoomOutBtn"
                :title="t('dashboard.worldmap_zoom_out')" style="width: 28px; height: 28px; padding: 0; font-size: var(--text-md)">−</button>
        <button v-if="zoom > 1" type="button" class="btn btn-ghost btn-sm" @click="resetView"
                :title="t('dashboard.worldmap_zoom_reset')" style="width: 28px; height: 28px; padding: 0; font-size: 10px">⟲</button>
      </div>

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
        <template v-if="tooltip.gateway">
          <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); margin-bottom: 4px">
            {{ tooltip.gateway.gatewayPeerName }}
          </div>
          <div style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <div v-if="!portal && tooltip.gateway.gatewayIp" style="margin-bottom: 2px">
              <span :style="tooltip.gateway.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                    style="font-size:9px">{{ tooltip.gateway.gatewayOnline ? '●' : '○' }}</span>
              <span style="font-family: var(--font-mono); color: var(--fg2)">{{ tooltip.gateway.gatewayIp }}</span>
            </div>
            <div v-if="portal">
              <span :style="tooltip.gateway.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                    style="font-size:9px">{{ tooltip.gateway.gatewayOnline ? '●' : '○' }}</span>
              {{ tooltip.gateway.gatewayOnline ? t('topology.portal_connected') : t('topology.portal_disconnected') }}
            </div>
            <div v-else>{{ tooltip.gateway.gatewayLastSeenAt ? t('topology.handshake', { when: relativeTime(tooltip.gateway.gatewayLastSeenAt) }) : t('topology.no_handshake') }}</div>
            <div v-if="!portal && tooltip.gateway.gatewayOnline">{{ t(trafficLabelKey(tooltip.gateway)) }}</div>
            <div v-if="tooltip.gateway.sites.length > 1" style="margin-top: 2px">{{ tooltip.gateway.sites.length }} {{ t('topology.networks_short') }}</div>
          </div>
        </template>
        <template v-else>
          <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); margin-bottom: 4px">
            {{ tooltip.site.name }}
          </div>
          <div v-if="tooltip.site.cidr" style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg2)">
            {{ tooltip.site.cidr }}
          </div>
        </template>
      </div>
    </div>
  `,
  created() {
    this.W = W; this.H = H; this.HUB_R = HUB_R; this.SITE_R = SITE_R; this.GRID_CELL = GRID_CELL;
  },
});
