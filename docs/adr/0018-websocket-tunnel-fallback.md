# ADR-0018 — WebSocket-tunnel fallback for WireGuard traffic (wstunnel-inspired)

**Status:** Proposed
**Date:** 2026-07-20
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0015](0015-builtin-tls-termination.md) (built-in TLS termination — this feature shares its port and its "no mandatory external process" stance), [#30](https://github.com/chriscohnen/islandr/issues/30) (ACME auto-provisioning — separate concern, same TLS-boundary neighborhood), `acl/RdpProxyEndpoint.java` (the existing WebSocket-relay precedent this design follows)

## Context

Some networks — hotel and corporate Wi-Fi being the common case — allow outbound HTTPS but block WireGuard's UDP traffic outright, whether by port or by protocol fingerprint. An operator whose road-warrior peers hit this has no recourse today: WireGuard's own client only ever speaks UDP to a configured endpoint.

[wstunnel](https://github.com/erebe/wstunnel) is the well-known tool for this class of problem: it wraps arbitrary UDP/TCP traffic inside a WebSocket connection, which — served over TLS on port 443 — is indistinguishable to a firewall or DPI box from ordinary browser HTTPS traffic. The idea raised for islandr: since islandr's hub already terminates HTTPS on 443 for its own web UI ([ADR-0015](0015-builtin-tls-termination.md)), it could also serve a WebSocket endpoint on that same port that unwraps WireGuard's UDP packets, without requiring the operator to stand up and babysit a separate wstunnel server process.

### Why this isn't "run the real wstunnel binary"

The obvious approaches — spawning the actual `wstunnel` binary as a supervised subprocess, or requiring the operator to front islandr with an externally-configured wstunnel + reverse-proxy path-routing setup — were both considered and rejected early. The first reintroduces exactly the "extra external moving part" ADR-0015 fought to remove for TLS; the second makes the feature something the operator has to assemble themselves, not something islandr provides. This ADR's premise is that islandr owns the server-side endpoint natively, the same way ADR-0015 made islandr own TLS termination natively rather than delegating it to a fronting proxy.

### Why this isn't "reimplement wstunnel's wire protocol either"

Reimplementing wstunnel's actual UDP-over-WebSocket framing to interoperate with the real, unmodified `wstunnel` client binary was investigated and found to be a bigger commitment than it first appears:

- **wstunnel has no published wire-protocol specification.** Its documented surface is CLI flags only — `--http-upgrade-path-prefix` (client-side upgrade path), `--restrict-http-upgrade-path-prefix` (server-side allow-list, used as a shared secret), `--http-upgrade-credentials` (Basic Auth on the upgrade), `--tls-sni-override`. The actual per-datagram framing and any connection multiplexing exist only as undocumented behavior in its Rust source.
- **It has already had one confirmed breaking rewrite:** v7.0.0 was a full rewrite, explicitly incompatible with earlier versions (currently at v10.6.2 as of this writing).
- Matching it "for real" would mean reverse-engineering undocumented framing from a moving-target codebase, then re-verifying that reverse-engineering against every future wstunnel release islandr wants to stay compatible with. That is open-ended maintenance, not a one-time port.

Given that, this ADR treats wstunnel as **prior art and inspiration for the shape of the solution** (WS binary frames carrying raw datagrams, an upgrade-path prefix as a shared secret), not as an interoperability target. islandr defines and owns its own protocol on this endpoint; a real `wstunnel` client binary is not expected to work against it. **The exact wire format is deliberately left open — see "Open questions" below** — this ADR decides the architecture, not the byte layout.

## Decision

islandr's hub gains a native WebSocket endpoint, structurally identical in shape to the existing `acl/RdpProxyEndpoint.java` (Quarkus WebSockets-Next: `@WebSocket`, `@OnBinaryMessage`, a virtual thread relaying the reverse direction), that relays binary WebSocket frames to and from the hub's local WireGuard UDP port over loopback.

- **Shares port 443 with the web UI.** This is the entire point — a separate dedicated port would be trivially blockable by the same firewall this exists to get around, and gains nothing over just opening the WireGuard UDP port directly. Path-based routing inside islandr's own HTTP server (the same server ADR-0015 already terminates TLS on) distinguishes this endpoint from UI/API traffic.
- **One shared, admin-configurable path per hub — not per-peer.** WireGuard's own Noise handshake, keyed per peer, is what actually authenticates a connection; the path segment only needs to keep the endpoint from being casually fingerprinted by an automated DPI scan, not carry its own per-identity authentication. A per-peer path would duplicate revocation machinery islandr already has (disabling/removing a peer's WireGuard key) for no added security — a leaked path is useless to anyone without a valid WireGuard peer key behind it. The path lives in Settings, rotatable by the admin, the same way the TLS certificate is managed there.
- **Off by default, opt-in in Settings.** This endpoint is new attack surface reachable pre-WireGuard-handshake (an internet-facing WebSocket upgrade target, even if its path is a secret). Consistent with islandr's existing pattern of admin-driven settings changes rather than always-on features that widen the default attack surface.
- **No new process, no filesystem dependency on an external binary.** The relay is in-process JVM code, following the same architectural principle as ADR-0015's `KeyStoreProvider`: islandr does the thing itself rather than shelling out to, or depending on the presence of, an external tool.

### Open questions (left for implementation, not decided here)

- **Wire format.** Two live options, deliberately not chosen yet: (a) reverse-engineer wstunnel's current UDP-over-WS framing closely enough for the real `wstunnel` client to work against islandr today, accepting the upstream-drift risk described above; or (b) a simpler islandr-native framing (WS binary frame = one raw UDP datagram, path prefix = shared secret, optional Basic Auth on upgrade), with islandr shipping its own small client binary later. Given this is an optional, occasional-use fallback (invoked only when an operator is on a network actively blocking WireGuard), the cost of choosing wrong here is low and does not block accepting the architecture in this ADR.
- **Client delivery**, if option (b) above is chosen: what a downloadable islandr-provided client looks like (language/runtime, release pipeline alongside server builds) is out of scope here and would be its own follow-up issue.
- **Multiplexing / connection lifecycle**: whether one WebSocket connection maps to one continuous UDP conversation for the tunnel's lifetime, or needs reconnect/resume handling for flaky client networks (hotel Wi-Fi is exactly the environment where connections drop) — an implementation-level detail once the wire format is chosen.

## Alternatives considered (Pugh Matrix)

Baseline: **native in-process WebSocket relay endpoint, own protocol, off by default** (the decision).

| Criterion (weight) | Native in-process relay (baseline) | Spawn real `wstunnel` binary as subprocess | Require external reverse-proxy + operator-run wstunnel | Reimplement wstunnel's actual wire protocol | Do nothing (docs only) |
|---|:---:|:---:|:---:|:---:|:---:|
| Zero mandatory external process (6) | 0 | −1 | −1 | 0 | +1 *(trivially, nothing to run)* |
| Compatible with the real, unmodified `wstunnel` client (5) | −1 | +1 | +1 | +1 | 0 |
| Immune to upstream wstunnel protocol drift (4) | +1 | 0 *(binary version pinning still a concern)* | 0 | −1 | +1 |
| Implementation + ongoing maintenance cost (4) | 0 | +1 *(no protocol work, just process supervision)* | +1 *(no islandr code at all)* | −1 | +1 |
| Consistent with ADR-0015's "islandr owns this natively" precedent (3) | 0 | −1 | −1 | 0 | −1 |
| Operator effort to actually use it (2) | 0 | 0 | −1 *(operator must assemble proxy + wstunnel themselves)* | 0 | −1 |
| **Weighted total** | **0** | **1** | **0** | **1** | **10** |

Notes:

- **"Do nothing (docs only)"** wins the numeric score by a wide margin, unsurprisingly — the same shape of result ADR-0015 and ADR-0012 found for their most conservative alternatives. It fails the actual requirement by construction: the goal is for islandr to own this capability, not to leave the operator to wire it up externally. Included for completeness, not as a live contender.
- **Spawn real `wstunnel` binary** and **reimplement its wire protocol** both score respectably and are the two paths that would deliver real-client compatibility — this is exactly the trade-off named in Context. Both were rejected for the reasons given there: a subprocess dependency undoes ADR-0015's single-process stance, and protocol reimplementation is open-ended maintenance against an undocumented, previously-broken spec. Either could still be revisited if the "open questions" resolution above lands on option (a).
- **External reverse-proxy + operator-run wstunnel** is the status quo workaround available today without any islandr change at all — always still possible for an operator who prefers it, same as running islandr behind Caddy/Traefik remains possible after ADR-0015. This ADR adds a path that doesn't require it, exactly as ADR-0015 did for TLS.

## Consequences

**Positive**

- Operators on WireGuard-hostile networks get a same-port HTTPS-indistinguishable fallback without running or trusting a second process.
- No new attack surface by default — the feature does not exist for a deployment until explicitly enabled.
- Follows the same "islandr owns this in-process" architectural line as ADR-0015 (TLS) and the hub-only enforcement model of ADR-0005, rather than introducing a new externally-dependent moving part.

**Risks created**

- **R-160** — The WebSocket upgrade endpoint, once enabled, is reachable from the internet pre-WireGuard-handshake; an unpatched flaw in the relay code is now internet-facing attack surface. Mitigation: off by default; the relay does no protocol interpretation of its own beyond framing (it forwards opaque bytes to the local WireGuard UDP port, which does its own authenticated handshake) — there is no parsing of attacker-controlled application data to exploit beyond the framing layer itself.
- **R-161** — A leaked or guessed path segment lets an attacker reach the upgrade endpoint, though not the VPN itself (WireGuard's handshake still gates access). Mitigation: path is admin-rotatable in Settings, exactly like the TLS certificate; treated as a low-value secret whose compromise degrades DPI-evasion, not access control.
- **R-162** — Choosing wire-format option (a) (wstunnel-compatible framing) commits islandr to tracking an undocumented, previously-broken upstream protocol indefinitely. Mitigation: deferred as an explicit open question rather than decided now; if chosen, pin and document the exact wstunnel version/commit verified against, and treat future incompatibility as an accepted, documented limitation of an optional fallback feature — not a regression requiring emergency response.
- **R-163** — If wire-format option (b) is chosen, the feature is not usable until islandr also ships a client, which is unscoped follow-up work; the server-side endpoint alone does not deliver the operator-facing capability. Mitigation: tracked explicitly as a dependency in the eventual GitHub issue breakdown, not silently assumed complete when the server endpoint ships.

**Accepted trade-offs**

- No interoperability with the real `wstunnel` client is guaranteed by this ADR. An operator who wants to use their existing wstunnel install unmodified must continue to front islandr with their own reverse-proxy + wstunnel setup (the status-quo workaround), at least until/unless the open wire-format question is resolved toward option (a).

## References

- [wstunnel](https://github.com/erebe/wstunnel) — the tool this feature takes its architectural shape from.
- [ADR-0015](0015-builtin-tls-termination.md) — built-in TLS termination; this feature shares its port and its "no mandatory external process" reasoning.
- `acl/RdpProxyEndpoint.java` — the existing Quarkus WebSockets-Next relay pattern this design follows structurally.
- [ADR-0005](0005-hub-only-firewall.md) — hub-only enforcement model; this feature adds a transport path to the hub, not a new enforcement point.
