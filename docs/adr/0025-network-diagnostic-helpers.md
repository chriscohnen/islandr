# ADR-0025 — Network diagnostic helpers (ping / path latency) via unprivileged-shell CLI tools

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0011](0011-process-privilege-model.md) (privilege model this must not widen), [ADR-0005](0005-hub-only-firewall.md) (why a hub-originated probe is a faithful test of the real traffic path), [ADR-0012](0012-docker-socket-proxy.md) (Docker execution path), [ADR-0014](0014-device-discovery.md) (closest sibling — same "stay unprivileged, degrade honestly" posture for a network-probing feature), [ADR-0023](0023-resource-dns-resolver-hand-rolled.md) (precedent for a feature that silently disables itself with an actionable message rather than crashing when a host-level dependency is missing), Atlas view (`AtlasResource`/`AtlasDto`/`AtlasView.js`, the graph this hooks into).

## Context

Today, answering "why can't peer X reach resource Y?" has no tool inside Islandr — the admin has to SSH into the hub and run `ping`/`traceroute` by hand. That is exactly the complaint web front-ends for WireGuard are built to answer in the first place — that reading the state means logging in and typing a command; Islandr does not currently do better for network reachability, only for WireGuard/ACL state.

Two things make a hub-originated probe unusually meaningful here, not just a cosmetic nicety:

- **Hub-only firewall (ADR-0005)** means every real request — including site-to-site traffic — transits the hub. A ping/traceroute *from the hub* to a resource's IP tests the exact path a real, ACL-permitted peer's traffic would take. That is not true of a controller-originated probe in a full-mesh WireGuard topology, where the controller sits outside the actual data path.
- **It answers a different question than what already exists.** Peer connection status (Connected/Stale/Disconnected) tells you the WireGuard tunnel's own health. It says nothing about the *other* half of a support case: is the resource behind a site gateway peer actually alive and routable right now? A hub-originated probe fills exactly that gap, and cleanly separates "reachability problem" from "ACL/grant problem" — a ping succeeding does not mean a given peer's grant would let its traffic through, but a ping *failing* rules out ACL misconfiguration as the cause immediately.
- **Latency, not just up/down, is the actual ask.** A single reachability bit ("resource is up") is worth much less than being able to compare — is this peer→resource path consistently slower than another, did it get worse after a change. `ping -c N` gives min/avg/max/mdev over a sample in one shot, which is enough to support that comparison without new infrastructure.

The constraint this has to fit inside is ADR-0011: Islandr runs as an unprivileged user; its only escalation is a `sudoers` file scoped to exactly `wg`/`nft`. Real ICMP echo traditionally needs `CAP_NET_RAW`, which is exactly the kind of privilege widening ADR-0011 exists to prevent.

A second constraint surfaced while scoping this: **not every diagnostic CLI tool is actually present.** `mtr` and `traceroute` are both optional packages, commonly absent on minimal server installs. More concretely, Islandr's own Docker image (`quay.io/quarkus/quarkus-micro-image`, see the project `Dockerfile`) is deliberately stripped to glibc + zlib + CA certs and has *no* network diagnostic tools at all — that is a design choice (keep the image minimal), not an oversight, and this feature must not quietly assume otherwise. `ping` (from `iputils-ping`) is the one tool close to universally present on a real Linux host, and `tracepath` ships in the same `iputils` package family — where `ping` exists, `tracepath` very likely does too, and it does not need root at all on Linux (UDP + Path-MTU-Discovery, not raw ICMP).

## Decision

**Add an admin-triggered, on-demand ping/path-latency probe, executed via the same tightly-scoped shell-out pattern ADR-0011 already establishes for `wg`/`nft`, surfaced on the Atlas view as an action on a specific Resource (optionally traversing its Site's gateway peer) — with an explicit "not available, install X" state instead of a silent failure when the underlying tool is missing.**

### 1. Tool choice — `ping` + `tracepath` as the baseline, `mtr` as an optional upgrade, never a requirement

- **`ping -c N -W timeout <ip>`** is the baseline reachability + latency probe. Near-universal, gives min/avg/max/mdev RTT over the sample in a single parseable report — sufficient for the actual ask (compare latency between two probes), no new infrastructure needed.
- **`tracepath <ip>`** is the baseline per-hop path tool — same `iputils` package as `ping`, and (unlike classic `traceroute`) does not need root on Linux at all, so it may not even need the `sudoers` entry.
- **`mtr --report --report-cycles N -n <ip>`**, if detected on `PATH`, is offered as a richer alternative (per-hop loss % *and* aggregated latency over multiple cycles) — but the feature is fully functional without it. Nothing in the design assumes `mtr` exists.
- **`traceroute`** is deliberately not depended on — it is the least-likely-present of the four tools discussed and `tracepath` already covers the same job on Linux.

### 2. Availability is checked, not assumed

At startup (and re-checked on the diagnostics page, mirroring how the System → DNS page reports `running` separately from `enabled`), Islandr probes `PATH` for `ping`/`tracepath`/`mtr`. Whichever are present light up as available actions; whichever are missing show an explicit, actionable message — *"tracepath nicht gefunden — installiere `iputils-tracepath`, um Pfad-Diagnose zu aktivieren"* — instead of a broken button or a silent no-op. Same posture as the DNS resolver's own `CAP_NET_BIND_SERVICE` bind-failure handling (ADR-0023): the feature degrades honestly, the app never crashes over a missing host dependency, and the fix is spelled out.

### 3. Privilege — same `sudoers` scope as `wg`/`nft`, native execution only

- **Native/systemd deployment:** originally planned as a `sudoers` entry scoped to the `ping` invocation shape (mirroring `wg`/`nft`) — **corrected during implementation**: neither `ping` nor `tracepath` is actually run through `sudo`. `tracepath` needs no elevation at all on Linux. `ping` does not either on a modern host: `iputils` normally ships the binary with a `cap_net_raw` file capability rather than `setuid root`, and most distributions additionally set the `net.ipv4.ping_group_range` sysctl to permit an unprivileged ICMP socket outright — the assumption that `ping` needs the same escalation as `wg`/`nft` was simply wrong. A host that genuinely lacks both fails the call with "Operation not permitted", surfaced honestly as the same "tool not available" state as a missing binary (§2) rather than silently routed around with `sudo` — the fix is local to the host (`setcap`/sysctl), not a new sudoers line. Net effect: **zero new sudoers entries** for this ADR, an even smaller footprint than originally decided.
- **Docker deployment:** routed through `islandr-proxy` (ADR-0012) exactly like `wg`/`nft` — a new pair of handlers on the existing Unix-socket API, but unlike `wg`/`nft` these do **not** escalate through the proxy's own `sudo` either (same reasoning as the native path — the proxy's `Executor.Run` takes an explicit per-call `sudo` flag). `islandr-proxy.sudoers` was not extended for network diagnostics.
- A new `NetworkDiagnosticsAdapter` interface (real / mock / socket) mirrors `WgAdapter`'s existing three-way split — same pattern, same reasoning, nothing novel introduced.

### 4. Target validation — known Peers/Resources only, never a free-text IP

The probe target is selected from Islandr's own data (a `Resource`, optionally via its `Site`'s gateway peer), never typed in as an arbitrary address. Same posture as the DNS resolver only ever answering for a known `Resource` (ADR-0023) and Device Discovery only ever scanning a site's own *declared* CIDR (ADR-0014): the hub does not become a general-purpose "ping anything from here" primitive reachable by anyone who can reach the admin console.

### 5. GUI placement — Atlas view, probe as an action on an existing graph edge

**Atlas** (`AtlasResource`/`AtlasDto`/`AtlasView.js`), not the Geo Map, is where this lives. Atlas already models exactly the shape this needs — `SiteNode` (carrying `gatewayPeerId`), `ResourceNode`, and the `Edge`s between them — because it is already a reachability graph, not a location map. Triggering a probe becomes a context action on a `ResourceNode`: Islandr resolves the path (hub → the resource's site gateway peer, if any → the resource) and highlights exactly that chain on the existing graph while the probe runs, then annotates the result (latency, or per-hop breakdown) inline. The Geo Map is about physical placement, not the logical path a request takes, and is the weaker fit for "which connection is being tested" — a pin lighting up green does not communicate *what was actually probed* the way an already-drawn edge does. Nothing about this ADR precludes also surfacing a probe result on the Geo Map later as a secondary view; it is just not where the primary interaction belongs.

### 6. Scope for v1 — on-demand only, no persisted history

Each probe is triggered explicitly by an admin and shown once, live. **No background/scheduled probing, no persisted time series in this decision.** An admin who wants to compare "is it slower than yesterday" re-runs the probe and compares by eye. Persisted historical latency (a trend graph, akin to the existing activity heatmap's storage model, ADR-0016) is a plausible, separately-scoped follow-up, not a launch blocker — it adds a retention/storage question this ADR does not need to answer to deliver the core troubleshooting value.

## Alternatives considered (Pugh Matrix)

Baseline: **A — unprivileged-shell `ping`/`tracepath`, admin-triggered, target-validated, surfaced on Atlas** (the decision).

| Criterion (weight)                                        | A: shell-out baseline | B: raw ICMP/ARP natively in Islandr | C: bundle a custom static helper binary | D: assume `mtr`/`traceroute` are installed, no detection | E: no tool — keep "SSH into the hub" |
|-------------------------------------------------------------|:----------------------:|:-----------------------------------:|:----------------------------------------:|:-----------------------------------------------------------:|:--------------------------------------:|
| Stays inside the ADR-0011 privilege model (5)                | 0                      | -1                                   | 0                                          | 0                                                             | 0                                       |
| Works out of the box on every deployment incl. Docker (4)     | 0                      | 0                                    | +1                                         | -1                                                            | 0                                       |
| Solves the actual latency-comparison ask (4)                  | 0                      | 0                                    | 0                                           | 0                                                             | -1                                      |
| Consent / no new remote-scan surface (3)                      | 0                      | 0                                    | 0                                           | 0                                                             | +1                                      |
| Implementation cost (2)                                       | 0                      | -1                                   | -1                                          | +1                                                            | +1                                      |
| Matches the stated "user rights + sudo CLI" architecture (2)  | 0                      | -1                                   | -1                                          | 0                                                             | 0                                       |
| **Weighted total**                                            | **0**                  | **−7**                               | **−2**                                     | **−4**                                                        | **−2**                                  |

Honest reading:

- **B (raw ICMP/ARP directly in the JVM)** needs `CAP_NET_RAW` (or a JNI/raw-socket library) — a real widening of ADR-0011's model, and Java has no standard-library raw-socket API, so it also costs more to build than shelling out to a tool the OS already ships. Clear reject.
- **C (a small statically-linked Go helper binary, shipped and privileged like `islandr-proxy`)** is honestly the most *portable* option — Islandr would control its own dependency instead of hoping `ping` exists — and its narrow, single-purpose surface is arguably even more auditable than granting `sudo` to a general-purpose `ping` binary. It loses on two things: it is a new artifact to build, sign, and version alongside `islandr-proxy`, and it contradicts the explicitly stated preference to keep Islandr itself at user rights and do everything else through `sudo`-scoped CLI shells rather than a bespoke privileged component. Worth revisiting if the CLI-availability story ever turns out to be a bigger problem in practice than assumed here.
- **D (assume `mtr`/`traceroute`, skip the availability check)** is the simplest code but fails exactly the case this ADR was written to avoid repeating: a feature that looks broken on a large share of real installs (and unconditionally on Docker) instead of degrading honestly. Rejected on portability.
- **E (no tool, status quo)** avoids all new risk and cost but does not solve the actual problem — the honest "safe and useless for the goal" alternative, same shape as ADR-0014's rejected "manual CSV import" option.

A wins by staying inside the privilege model, working everywhere including the deliberately minimal Docker image, and matching the deployment philosophy already established for `wg`/`nft` — at the cost of depending on `iputils` being present on native installs, which is mitigated by detecting it and degrading honestly rather than assuming it.

## Consequences

**Positive**

- Closes a real, previously terminal-only gap: an admin can now answer "is this a reachability problem or an ACL problem" without SSH access to the hub.
- A hub-originated probe is a faithful test of the real traffic path specifically because of the hub-only-firewall model (ADR-0005) — this is more meaningful here than the same feature would be in a full-mesh topology.
- Zero widening of the ADR-0011 privilege model: same `sudoers`-scoping discipline as `wg`/`nft`, `tracepath` needs no elevation at all.
- Degrades honestly when the underlying CLI tool is missing, instead of a broken button — same posture as the DNS resolver's bind-failure handling.
- Reuses the existing Atlas graph and the existing `WgAdapter`-style real/mock/socket adapter pattern — no new architectural concept introduced.

**Risks created**

- **R-181** — A probe target must stay restricted to known Resources (never a free-text address) or the feature becomes an authenticated internal-network-probing primitive reachable by anyone who can reach the admin console — the same recon-primitive shape as R-141 (Device Discovery). Mitigation: target selection is always resolved from Islandr's own `Resource`/`Site` data, admin-only, and every probe is audit-logged (actor, target, result), same discipline as discovery scans.
- **R-182** — `ping`/`tracepath`/`mtr` availability varies by host and is explicitly *not* guaranteed on native installs (only guaranteed inside the project-controlled `islandr-proxy` image for Docker deployments). An admin on a host missing `iputils` sees a degraded feature. Mitigation: runtime detection with an actionable "install X" message rather than a silent failure or a crash; the feature's absence never blocks anything else.
- **R-183** — A rapid, repeated probe trigger (e.g. scripted against the admin API) could become a low-grade probe-flood against a resource or its upstream network. Mitigation (ties **T-018**): admin-only, rate-limited per resource (mirrors Device Discovery's one-active-scan-per-site guardrail, R-142/T-014), bounded `ping`/`tracepath` sample counts fixed server-side, not admin-tunable beyond a small cap.

**Accepted trade-offs**

- No persisted latency history in v1 — comparison is "run it twice and look," not a trend graph. Accepted as the cheaper, sufficient answer to the actual ask (troubleshoot/compare on demand); a stored time series is a real but separately-scoped follow-up.
- `mtr`'s richer per-hop, multi-cycle view is opportunistic, not guaranteed — an admin without `mtr` installed only gets `tracepath`'s single-cycle-per-hop view. Accepted; `tracepath` still answers "where does it break," just with less statistical depth than `mtr`.
- Native-install tool availability is a real, admin-facing dependency this ADR does not eliminate, only detects and reports honestly. Accepted as the cost of staying inside the "unprivileged Islandr, sudo-scoped CLI tools" architecture (Alternative C would remove this dependency entirely, at the cost of a new bundled component — explicitly not chosen here).

## Follow-ups (traceability per the docs contract)

- ✅ **R-181, R-182, R-183** added to [arc42 §11](../arc42/11-risks-and-technical-debt.md) (Low priority, alongside their ADR-0014 siblings R-140/R-141/R-142).
- ✅ **T-018** (network-diagnostics probe abused as a recon primitive / probe-flood) added to the [§8.1 STRIDE threat model](../arc42/08-crosscutting-concepts.md), cross-referenced from its mitigation in §8.2 and from R-181/R-183.
- ✅ Implemented: `NetworkDiagnosticsAdapter` (real/mock/socket, mirroring `WgAdapter`), the ping/tracepath/mtr endpoints on `ResourceResource` and `PeerResource` (target always an existing `Resource` or the site's own gateway `Peer`, 3s per-target cooldown shared across all three probes, fixed sample counts, audit-logged), the `islandr-proxy` `net_ping`/`net_tracepath`/`net_mtr`/`net_availability` ops, and the Atlas UI (a persistent Hub node, a rim-anchored gateway diamond that is itself pingable when the site has a peer, and a "Test connection" action showing the probed hub → site-gateway → resource path as an overlay on the graph, not just in a dialog).
- ✅ §1's `mtr` "opportunistic upgrade" is now real, not just planned: `mtr --report --report-cycles 4 -n <ip>`, never `sudo` (same reasoning as `ping`), surfaced in the Atlas dialog only when `GET /api/v1/diagnostics/availability` reports it present.
- ✅ §5 update (2026-08-22 feedback): the gateway (site-peer) diamond sits exactly on its circle's rim, not floating just inside it, and is itself a valid diagnostics target — clicking it (when the site actually has a gateway peer) focuses it and offers "Test connection" the same way a Resource does, via new `PeerResource` endpoints. A persistent Hub node was added to the graph (previously absent — Atlas only ever drew grants), anchored above the packed site cluster; the probed path is drawn as a highlighted overlay directly on the diagram (hub → gateway diamond → resource/peer), colored by reachability, not only described in the dialog's text.
- ⏳ Capture in the spec: a new Business Rule for target-validation (no free-text IP) and the audit-log entry shape, plus a runtime scenario in arc42 §6 showing the hub → site-gateway-peer → resource probe path.
- ⏳ arc42 §9's ADR table/cross-cutting-consequences list stops at ADR-0019 already (ADR-0020–0024 are likewise not yet back-filled) — left as-is rather than adding only ADR-0025 out of sequence; a future pass should catch up the whole range at once.

## References

- [ADR-0011](0011-process-privilege-model.md) — the privilege model this must not widen.
- [ADR-0005](0005-hub-only-firewall.md) — why a hub-originated probe tests the real traffic path.
- [ADR-0012](0012-docker-socket-proxy.md) — the Docker execution path this reuses.
- [ADR-0014](0014-device-discovery.md) — closest sibling: same "unprivileged, target-bound, honestly degrading" posture for a different network-probing feature; its R-140/R-141/R-142 and T-013/T-014 are the direct precedent for this ADR's own risk numbering.
- [ADR-0023](0023-resource-dns-resolver-hand-rolled.md) — precedent for a feature that reports a missing host-level capability/tool honestly instead of crashing or silently no-op'ing.
- `AtlasResource.java` / `AtlasDto.java` / `AtlasView.js` — the existing reachability graph this hooks into.
- `iputils` upstream (`ping`, `tracepath`) — the tools this depends on being present natively.
