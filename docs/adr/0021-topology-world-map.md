# ADR-0021 — World-map topology view: air-gapped SVG projection, manual geocoding only

**Status:** Accepted
**Date:** 2026-07-26
**Deciders:** Christian Cohnen
**Target release:** vX (0.15.0 was the original target label in TODO.md; not committed to a specific release here)
**Relates to:** [Issue #11](https://github.com/chriscohnen/islandr/issues/11), `TopologyDiagram.js` (the existing radial diagram this adds an alternative view alongside)

## Context

Operators with geographically distributed WireGuard sites (office in Frankfurt, home-office in Hamburg, cloud VM in Amsterdam) currently only see the existing radial hub-and-spoke `TopologyDiagram.js` — logically correct, but it carries no sense of *where* anything physically is. Issue #11 asks for a world-map view as an alternative, not a replacement: site-peers (gateways) as pins at their maintained location, active tunnels as connecting lines, with the existing radial view remaining the default for logical/ACL-oriented browsing.

Two prior decisions already narrowed this scope before this ADR was written:

- **Geocoding lives on the site-peer, not the site** (TODO.md, corrected during 0.15.0 prep): a `Site` is a logical CIDR grouping with no physical location; the gateway `Peer` is the physical device. `Peer.lat`/`lng`/`locationLabel` (populated only for `type=site`) via migration V47.
- **Hub geocoding already exists**: `Settings.hubLat`/`hubLng`/`hubLocationLabel` (V33), already surfaced in the radial diagram's hub label.

Both are **manually entered** (paste `"lat, lng"` into the peer/hub form) — there has never been an automatic IP→location resolution step anywhere in islandr, and this ADR's scope explicitly keeps it that way (see Decision). A user-supplied design proposal for this feature suggested adding MaxMind GeoLite2 (`.mmdb`, offline, no external API call at request time) to auto-resolve locations from peer endpoint IPs, particularly for roaming client peers with no fixed site. That is **out of scope for this ADR** — confirmed via direct question at authoring time — because it introduces a new operational component (a `.mmdb` file that needs periodic redistribution/updates, a license/redistribution check for GeoLite2's EULA, and a fallback story for CGNAT/private endpoint IPs that resolve to nothing useful) for a benefit (auto-locating peers nobody asked to place on the map) that the current scope — fixed sites with an admin who already knows where they are — doesn't need. If a future issue asks specifically for roaming-peer auto-location, that is this ADR's natural follow-up, not something to build speculatively alongside the map itself.

The rendering approach was also settled going in, precisely because of islandr's own constraints: it runs as a single native binary with no build step for the frontend (per [ADR-0002](0002-vue-without-npm.md)) and is designed to work in air-gapped/zero-network deployments — ruling out Leaflet + OpenStreetMap tiles (a live external tile-server dependency at runtime) before this ADR was even drafted.

## Decision

**A — Local TopoJSON country outlines, rendered as SVG `<path>` elements via a slim `d3-geo`-only dependency (projection functions only, not the full d3 bundle), manual geocoding only, no GeoIP.**

- A single pre-bundled `world-110m.json` (or equivalent low-resolution TopoJSON, target < 50 KB) ships with the app under `META-INF/resources` — no CDN fetch, works fully offline.
- `d3-geo`'s `geoMercator` (or `geoEquirectangular` — see note below) projection converts each site-peer's/hub's stored `lat`/`lng` into SVG x/y once per render; country borders render as `<path d="...">` filled with a semantic surface-token color (`fill: var(--surface-2)` or similar), themeable per [ADR-colors/dark-mode conventions already in `colors_and_type.css`].
- Connections between hub and site-peers render as slightly curved Bezier `<path>` arcs, reusing the same status-color convention already established in `TopologyDiagram.js` (`status-ok` green for an online gateway, muted/dashed for offline) — no new color vocabulary.
- The existing hub glow-pulse (`topo-hub-pulse`, the one ambient-motion element the design brief permits) anchors the hub pin on the map the same way it anchors the hub circle in the radial view.
- Map view is additive: a tab/toggle alongside the existing radial diagram (mirroring the Settings TLS tab pattern already used elsewhere), not a replacement. Per the original issue scope, it only renders/appears once at least 2 site-peers have coordinates set — otherwise there's nothing geographic to show and the tab stays hidden.
- **No GeoIP, no MaxMind, no `.mmdb`.** Peers/sites without manually-entered coordinates simply don't appear as map pins (same as today's behavior for anything without `lat`/`lng`). Roaming client peers (laptops, phones) are out of scope for map placement entirely in this ADR — they have no fixed location to plot in the first place, manual or automatic.

**Resolved at implementation time (same session):** hand-rolled equirectangular projection, no `d3-geo` dependency at all — the 3-line lat/lng→x/y formula needs no library, and ADR-0002's no-npm-build stance weighs against adding even a "slim" d3 sub-package for a formula this small. Land data is the `land` object from `world-atlas@2`'s `countries-110m.json` (not the `countries` object with political borders) — TopoJSON decoded once at authoring time into plain `[[lon,lat],...]` polygon rings (see `META-INF/resources/data/README.md` for the regeneration recipe), shipped as a 66 KB static JSON asset, no political borders at all. This sidesteps the border-dispute concern in **R-169** below more completely than "use a coarse resolution" alone — there is nothing to dispute when only coastlines are drawn. Equirectangular (not Mercator) was chosen for the same reason: zero-cost to compute, and pole distortion is irrelevant once the viewBox crops out the deep polar regions no site will ever be in.

## Alternatives considered (Pugh Matrix)

Baseline: **A — local TopoJSON + d3-geo projection, manual geocoding only** (the decision).

| Criterion (weight) | A: local TopoJSON + d3-geo, manual geo (baseline) | B: Leaflet + OSM tiles | C: A + MaxMind GeoIP auto-resolution | D: status quo (radial diagram only, no map) |
|---|:---:|:---:|:---:|:---:|
| Works fully offline / air-gapped (5) | 0 | −1 *(needs a live tile server at runtime; self-hosting a tile server is a materially bigger operational ask than a bundled JSON file)* | 0 | +1 *(nothing to break)* |
| No new runtime dependency on an external service (4) | 0 | −1 | 0 | +1 |
| Implementation effort (3) | 0 | +1 *(Leaflet handles projection, panning, zoom for free)* | −1 *(mmdb lookup service, update job, CGNAT fallback logic)* | +1 *(zero code)* |
| Matches actual current scope (fixed sites, admin already knows the location) (4) | +1 | 0 | 0 *(solves a problem — roaming-peer placement — nobody asked for yet)* | −1 |
| New operational component to maintain (mmdb redistribution/licensing) (3) | 0 | 0 | −1 | +1 |
| Bundle footprint (2) | 0 *(TopoJSON ~30-50KB + slim d3-geo)* | −1 *(Leaflet itself + tile-loading code)* | 0 | +1 |
| **Weighted total** | **1** | **−9** | **−4** | **4\*** |

\* D's positive score is the same pattern flagged in ADR-0016's matrix: it wins by having no cost, not by satisfying the requirement. Issue #11 exists specifically because the radial-only view doesn't answer "where are my sites" — D is included for completeness, not as a live contender.

- **B (Leaflet + OSM tiles)** was ruled out before this ADR was drafted, for the reason stated in Context: a live external tile-server dependency directly contradicts islandr's air-gapped/sovereign deployment story, the same category of concern that shaped [ADR-0002](0002-vue-without-npm.md) (no npm build step) and the DNS-01 manual mode in [ADR-0020](0020-dns01-challenge-with-manual-mode.md) (works with no external API access). Self-hosting a tile server to avoid the external dependency would itself be a new operational component larger than the map feature it enables.
- **C (add MaxMind GeoIP)** is a genuinely reasonable feature on its own merits — the input that prompted this ADR made a solid case for it — but it solves a problem outside today's actual scope (fixed sites with an admin who already knows where they are) at the cost of a new operational component (`.mmdb` file lifecycle, license terms, CGNAT/private-IP fallback with no good answer). Rejected for *this* ADR specifically, not rejected forever — flagged as a clean follow-up ADR if a future issue asks for roaming-peer auto-location.
- **A** wins by matching the problem actually in scope (plot sites/hub the admin already has coordinates for) at the lowest operational and dependency cost, at the accepted cost of not auto-placing anything the admin hasn't manually geocoded.

## Consequences

- A new topology-map component, `TopologyWorldMap.js`, alongside `TopologyDiagram.js` — sharing color/status conventions and CSS classes (`.topo` hub-pulse/hub-core/node-ring/node-label rules) but not layout code (equirectangular projection vs. radial polar layout). Rendered as an additional "Karte"/"Map" tab in `DashboardView.js`, alongside the existing "Topologie" and "Verbindungsaktivität" tabs, hidden until `worldMapAvailable` (≥2 geocoded site-peers).
- Backend gained two small, additive DTO fields, not a new endpoint: `DashboardDto.TopologySite.gatewayLat/gatewayLng` and `DashboardDto.Topology.hubLat/hubLon` (named to match the pre-existing `Settings.hubLat/hubLon` field naming, not the `Peer.lat/lng` convention used for `gatewayLat/gatewayLng` — the hub and site-peer coordinate fields predate this ADR and already used those two different names inconsistently; this ADR did not attempt to unify them). Reuses `Peer.lat/lng` and `Settings.hubLat/hubLon`, already populated by the existing manual-geocoding UI — no new database columns.
- One bundled static asset, `META-INF/resources/data/world-land-110m.json` (66 KB raw / ~20 KB gzipped, land-mass outlines only, no political borders — see below), ships with the app for the first time purely for this feature. A short `data/README.md` documents its provenance (`world-atlas@2`, ISC-licensed, Natural Earth public-domain source data) and the exact regeneration recipe (decode TopoJSON arcs, round coordinates, emit plain polygon rings) in case a higher-resolution source is ever substituted.
- If Alternative C (GeoIP) is ever pursued later, that would be the first ADR needing backend changes (a lookup service, a new settings toggle to enable/disable it, credential-free `.mmdb` distribution) — nothing here precludes it.
- **R-169** — A bundled land-outline file will drift from reality (coastline change is negligible at human timescales, but the *dataset* itself could be superseded) over years without a maintenance plan. Mitigation strengthened beyond what was planned: shipping only the `land` object (coastlines) rather than `countries` (political borders) removes the disputed-border class of drift entirely — a coastline doesn't move because of a diplomatic dispute. What remains is ordinary "is there a newer/better-resolution source" maintenance, accepted as a manual, infrequent task (see `data/README.md`'s regeneration recipe), not an automated pipeline.
- Follow-up work, deliberately out of this ADR's scope: GeoIP/MaxMind auto-resolution for roaming peers (Alternative C above) if a future issue asks for it; the traffic-flow visualization (line width + direction, tracked separately in TODO.md and [issue #34](https://github.com/chriscohnen/islandr/issues/34)) layered onto this map's connection arcs once built, reusing whatever mechanism that separate item settles on; pan/zoom interaction (v1 renders a fixed, non-interactive projection, same as the radial diagram's own capped-viewBox behavior for small topologies).

## References

- [Issue #11](https://github.com/chriscohnen/islandr/issues/11) — the feature this ADR backs.
- [ADR-0002](0002-vue-without-npm.md) — no-npm-build-step stance this ADR's dependency choice (slim d3-geo vs. hand-rolled projection) must respect.
- [ADR-0020](0020-dns01-challenge-with-manual-mode.md) — the "manual, no external API required" pattern this ADR mirrors by rejecting GeoIP for now.
- [ADR-0016](0016-peer-activity-heatmap-storage.md) — matrix-format precedent this ADR follows, including the "status quo wins on cost alone" caveat.
- `TopologyDiagram.js` — the existing radial diagram this adds an alternative view alongside; status-color and hub-glow-pulse conventions reused here.
- TODO.md, "Geolocation / Weltkarte" section — the two prior decisions (site-peer-not-site geocoding; hub geocoding) this ADR builds on, and the sibling "Topology-Diagramm: Traffic-Fluss visualisieren" item this ADR's connection arcs could later host.
