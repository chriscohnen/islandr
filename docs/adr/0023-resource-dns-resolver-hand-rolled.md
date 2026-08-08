# ADR-0023 — Resource-name DNS resolver: hand-rolled UDP/TCP server, not a library

**Status:** Accepted
**Date:** 2026-08-02
**Deciders:** Christian Cohnen
**Target release:** vX (unscheduled — not yet an issue; implemented ahead of a release/issue assignment)
**Relates to:** [ADR-0006](0006-resource-level-acl.md) (resource-level ACL — the grant model the resolver's response filtering reuses), [ADR-0014](0014-device-discovery.md) (device discovery — potential future name-source for the managed zone), [ADR-0019](0019-acme-hand-rolled-client.md) (hand-rolled-over-library precedent this ADR follows again)

## Context

This idea surfaced from a competitor review (a comparable product ships a built-in DNS server; islandr does not). The motivation lines up with an existing product rule: never surface raw CIDRs/IPs to end users. Resource names instead of IPs is the same idea applied client-side — typing `fileserver.sitename.islandr.internal` into an RDP client, browser, or `ssh` instead of `10.8.0.23` — plus an incidental benefit unrelated to islandr itself: many home/small-office networks have no maintained local DNS, so the hub can fill that gap for connected peers.

Today, `Settings.wgClientDns` (`SettingsView.js:874-876`) is a single freetext field written verbatim into the generated `.conf` — no validation beyond presence, no distinction between "resolve my resources" and "resolve everything else." Any resolver work has to decide what this field means before and after the feature exists.

### Scope, decided going in

- One DNS server, bound to the hub's tunnel interface (`wg0`) only, not the public interface — unreachable off-VPN, so no open-resolver/amplification exposure.
- Authoritative for a managed zone derived from Resource entities (e.g. `<resource-slug>.<network-slug>.islandr.internal`); everything else forwarded 1:1, unmodified, to admin-configured upstream server(s).
- No conditional-forwarding rules, no blocklists — that stays a separate, explicitly deferred Enterprise candidate ("DNS filtering / blocklists"), not bundled into this feature.
- Response filtering by ACL: a peer only gets an answer for resources it's actually granted (ADR-0006); everything else is `NXDOMAIN`, not a full-zone answer with access enforced only at the IP layer. Resource names leak network structure even without access, so a full zone visible to every peer would defeat the point of resource-level ACLs existing at all.

### What's newly decided here

One question was left open going in: hand-roll the wire-format handling, or pull in a library (`dnsjava` being the obvious example). This ADR settles that, and settles a companion Settings UX question alongside it: the existing `wgClientDns` freetext field gets less comfortable to use correctly once there's a second DNS concept (resolver zone vs. upstream) layered on top of it — common upstream choices (Quad9, Google, Cloudflare, AdGuard) deserve labeled quick-select entries rather than requiring the admin to remember IPs. That UX change is small and independent of the resolver's internals; it's listed under Consequences, not scored in the Pugh matrix below, since it's not a competing implementation *approach* — it's needed regardless of which resolver technology wins.

## Decision

**Hand-rolled DNS resolver — raw UDP (with TCP fallback for oversized responses), no third-party DNS library.** Consistent with the precedent set in [ADR-0019](0019-acme-hand-rolled-client.md): islandr already prefers narrow, self-owned protocol code over a general-purpose library dependency when the actually-needed protocol surface is small enough to stay auditable.

The needed surface here is smaller than ADR-0019's ACME client:

- **Wire format**: DNS message parsing/serialization for A/AAAA/CNAME queries and responses (RFC 1035 §4) — header, question section, resource records with the standard label-compression scheme. A few hundred lines, no cryptography involved (unlike the ACME case).
- **Authoritative path**: look up the queried name against the managed zone (Resource entities, ACL-filtered per requesting peer's source IP → known peer identity); answer directly if found.
- **Forwarding path**: anything not in the managed zone is forwarded byte-for-byte to the configured upstream resolver(s) over UDP, response relayed back to the original client unmodified (same transaction ID, same source port semantics) — no response synthesis, no caching in v1 (see Consequences).
- **Transport**: `java.net.DatagramSocket` bound to the hub's tunnel IP, port 53; a minimal TCP listener as a fallback path for the (rare, for this record-type set) truncated-response case, per RFC 1035's UDP/TCP duality requirement.

Concretely:

- New `DnsResolverService` (or similarly named class), started conditionally when the feature is enabled in Settings — not always-on, since not every deployment wants a second listening service.
- `Settings` gains: a boolean toggle for the resolver, the managed zone's base domain (default derived from install, editable), and a **separate** upstream-forwarder field — `wgClientDns` itself is never repurposed. It keeps meaning exactly what it always has (verbatim into the client `.conf`, including split-DNS `~domain` syntax); the resolver, when enabled, prepends the hub's own tunnel IP ahead of it so peers can actually reach the resolver at all. Where the resolver forwards non-zone queries is `wgClientDns`'s independent sibling field, not a reinterpretation of it — a `~domain` token is meaningful as something a *client* writes into its DNS line, but meaningless as a server-side forward target, so conflating the two fields would silently break split-DNS setups.
- Both the client-DNS field and the upstream-forwarder field get labeled quick-select entries for common public resolvers (Quad9 `9.9.9.9`/`2620:fe::fe`, Google `8.8.8.8`/`2001:4860:4860::8888`, Cloudflare `1.1.1.1`/`2606:4700:4700::1111`, AdGuard `94.140.14.14`/`2a10:50c0::ad1:ff`, IPv4 and IPv6 toggle independently) — a UI convenience, not a new architectural decision.
- Resource-to-name mapping stays admin-typed for v1 (MVP) — automatic name discovery via Device Discovery reverse-DNS/mDNS (ADR-0014) is explicitly deferred, not built here.

## Alternatives considered (Pugh Matrix)

Baseline: **hand-rolled UDP/TCP resolver** (the decision).

| Criterion (weight) | Hand-rolled (baseline) | `dnsjava` library | Status quo (freetext upstream-only field, no resolver) |
|---|---|---|---|
| Native-image risk (5) | +1 *(pure JDK sockets/byte handling, nothing to verify)* | −1 *(unverified GraalVM reachability for `dnsjava`'s server-side classes specifically — no prior islandr precedent, unlike acme4j's documented metadata in ADR-0019)* | +1 *(no resolver code to fail)* |
| New third-party dependency / supply-chain surface (4) | +1 *(nothing added)* | −1 *(dnsjava enters the tree; a DNS-protocol-parsing library is a meaningful attack surface to trust — it parses attacker-reachable network input)* | +1 *(nothing added)* |
| Implementation cost (3) | −1 *(RFC 1035 message format hand-written, including label compression)* | +1 *(library handles wire format, RR types, message building)* | +1 *(zero code)* |
| Auditability of a network-facing, attacker-reachable parser (4) | +1 *(narrow — only the record types this feature needs, every line islandr's own, same bar as the ACME/TLS precedent)* | 0 *(trusted upstream, but a general-purpose library parses far more record types/edge cases than islandr needs, widening the reviewed-vs-actually-used gap)* | +1 *(nothing to audit)* |
| Feature scope match (3) | +1 *(easy to keep deliberately minimal — no accidental feature creep from a general library's full RFC surface)* | 0 *(capable of much more than needed; discipline required not to grow scope just because the library supports it)* | −1 *(doesn't solve the underlying problem this ADR exists for)* |
| Resource-name UX this ADR exists to deliver (3) | +1 | +1 | −1 |
| **Weighted total** | **13** | **-2** | **6** |

Notes:

- **Status quo** scores respectably for the same structural reason it always does in this document's ADRs (see ADR-0015's and ADR-0019's matrices): not building something avoids every risk criterion by definition. It loses on the one criterion that motivates this ADR's existence.
- **`dnsjava`** loses primarily on the two criteria this codebase has consistently weighted heaviest for security-adjacent, network-facing code (see ADR-0019): native-image risk and auditability of code that parses attacker-reachable input. Unlike ADR-0019's `acme4j`, there is no equivalent GraalVM-reachability research finding for `dnsjava` to de-risk this — the unknown here is unmitigated, not merely unverified-in-practice. A library also invites building more than the deliberately-minimal scope calls for (full recursive resolution, DNSSEC, zone transfers) — capability islandr does not want here.
- The hand-rolled approach's implementation-cost loss is real but bounded: A/AAAA/CNAME plus one compression scheme is a small fraction of full RFC 1035, well short of ADR-0019's CSR/DER-encoding cost, which was already accepted at a larger scope.

## Consequences

- A new UDP listener (with TCP fallback) exists on the hub, bound to `wg0` only — the first network-facing listener in islandr scoped exclusively to the tunnel interface rather than the public one or loopback.
- **R-172** — A hand-rolled DNS message parser processes attacker-reachable (any connected peer) network input; a parsing bug could cause a crash, hang, or (worst case) a memory-safety-adjacent issue within JVM bounds. Mitigation: parser scope is deliberately minimal (query-side: standard question section only; response synthesis only for the authoritative path, which islandr fully controls; forwarded responses are relayed, not re-parsed and rebuilt, minimizing exposure to malformed *upstream* responses); bound only to the tunnel interface, so exposure is limited to peers already authenticated onto the VPN, not the open internet.
- **R-173** — Forwarding queries to an external upstream resolver leaks connected peers' DNS query patterns (visited hostnames) to that third party (Quad9/Google/Cloudflare/etc., or whatever the admin configures) exactly as any DNS-forwarding setup does. Not a new risk class introduced by islandr, but worth naming since the admin is choosing the upstream on peers' behalf. Mitigation: upstream is admin-configurable per deployment (not hardcoded to one vendor), and the resolver is opt-in — deployments that don't enable it keep today's client-side DNS behavior unchanged.
- **T-017** — New unauthenticated-by-protocol-design network listener (DNS has no built-in auth) on `wg0`. Closed by the interface-binding mitigation above: only WireGuard-authenticated peers can reach it at all — DNS's lack of app-layer auth is compensated by the tunnel's own authentication, the same trust boundary islandr already relies on for its `wg`/`nft` control plane (ADR-0005, ADR-0011).
- Settings UX changes: a new `dnsResolverUpstream` field, independent of `wgClientDns` (see Decision); `Settings.effectiveClientDns()` prepends the hub's tunnel IP to `wgClientDns` only when the resolver is enabled, used by both the real `.conf` generation and the Settings UI's live AllowedIPs preview so the two never disagree; labeled quick-select entries (IPv4 and IPv6 separately) for common resolvers on both DNS fields.
- Resource entities gain an admin-editable DNS-name field (MVP: manual entry only); no schema change needed for the ACL-filtering behavior, since it reuses the existing resource-level grant model from ADR-0006 as-is.
- No caching in v1 — every non-authoritative query round-trips to the upstream resolver. Acceptable for a first cut given islandr's peer counts are small-team scale, not attempted here to keep the initial surface minimal; a follow-up could add TTL-respecting caching if forwarding latency or upstream query volume ever becomes a real issue in practice, not preemptively.
- Automatic zone-name discovery from Device Discovery (ADR-0014) reverse-DNS/mDNS remains explicitly out of scope, a nice-to-have extension for later, not part of this decision.

## Implementation status

Noted here so the ADR doesn't overclaim relative to what actually shipped:

- **Shipped**: `DnsResolverService` (UDP + best-effort TCP listener), `DnsQueryHandler` (zone match + ACL-filtered resolution via `AclService.hasAnyGrant`), `DnsWireFormat` (hand-rolled RFC 1035 parse/build), `Resource.dnsName`, Settings UI toggle + zone + separate upstream-forwarder field, `Settings.effectiveClientDns()` auto-prepending the hub's tunnel IP into generated `.conf`s, a System → DNS page (`DnsView.js`, `DnsResource`) for status and manual lookup, plus unit/integration tests.
- **Revised from the original Decision text**: `wgClientDns` is never repurposed as the resolver's upstream — a design flaw caught before ship (it can hold split-DNS `~domain` syntax meaningless as a server-side forward target). `dnsResolverUpstream` is a fully independent field instead, defaulting to `1.1.1.1`/`8.8.8.8` when blank.
- **Known limitations**: listener binds IPv4-only (derived from `wgSubnet`), even when `wgSubnet6` is configured — AAAA answers still work once a query reaches it. Site-slug collisions across sites are unhandled. Port 53 needs `CAP_NET_BIND_SERVICE`; a bind failure is caught and logged, resolver stays off rather than crashing the app.
- **Fixed after first live use**: non-ASCII slugification (umlaut transliteration, e.g. ü→ue) to avoid collision-prone slugs; admin lookup now accepts a bare resource name or zone-less input, not just the exact FQDN; `dnsName` wired into the device-discovery bulk-import flow with an editable auto-suggest.
- **Follow-up: explicit site subdomain + per-resource flat opt-out.** Two related refinements to the naming scheme, both keeping the original derived-slug behavior as the default:
  - `Site.subdomain` (nullable) lets an admin fix a network's DNS label explicitly instead of relying on the live-derived slug — decouples it from the display name, so renaming a network no longer silently renames every resource's DNS name. Unique across the whole install (case-insensitive); falls back to `DnsQueryHandler.slugify(name)` when unset. UI: `SitesView.js`, same suggestion-not-silent pattern as the resource DNS-name field below.
  - `Resource.dnsFlat` lets an individual resource resolve as `<dnsName>.<zone>` directly, skipping the subdomain layer entirely — chosen over a per-site "flatten everything" toggle because the request was specifically for *some* resources within an otherwise-subdomained network. A flat name's uniqueness domain is the whole install (no site label left to disambiguate it), checked independently from the existing per-site domain for non-flat names — the two pools never collide since their resolved FQDN shapes differ, verified in `ResourceServiceDnsFlatTest`. `DnsQueryHandler.lookupZone`'s single-label-after-zone case, previously an unconditional `NO_MATCH`, now checks the flat pool.
  - Migrations V56 (`sites.subdomain`), V57 (`resources.dns_flat`). New tests: `SiteServiceSubdomainTest`, `ResourceServiceDnsFlatTest`, additions to `DnsQueryHandlerTest`.
- **Follow-up: live upstream answer for out-of-zone lookups.** The admin lookup tool's "not managed" result now performs an actual round-trip instead of just asserting the name would be forwarded upstream. `DnsWireFormat` gained `buildQuery` and `parseFirstAnswerAddress` (fails safe to null on malformed input rather than throwing); both are used *only* by the new `DnsResolverService#queryUpstreamForPreview`, a live, on-demand, admin-triggered query against the configured upstream(s) for the System → DNS page. Deliberately kept separate from `#forward` (the resolver's real listening path for actual peer queries), which still relays bytes completely unparsed both ways — the class doc's "narrows attacker-reachable parsing to the query side only" guarantee still holds for anything a peer can reach; only this new, low-volume, admin-only path parses upstream response bytes at all. The lookup response surfaces which upstream server answered plus the resolved IP, not just a yes/no. Not implemented: caching of upstream answers, and AAAA-preference/dual-query — a single A-record round-trip per lookup is enough for the admin preview use case.

## References

- [ADR-0019](0019-acme-hand-rolled-client.md) — hand-rolled-over-library precedent and Pugh-matrix criteria this ADR reuses (native-image risk, auditability of attacker-reachable/security-sensitive parsing code)
- [ADR-0006](0006-resource-level-acl.md) — resource-level ACL / RBAC0, reused as-is for per-peer response filtering
- [ADR-0014](0014-device-discovery.md) — device discovery; potential future automatic name source, explicitly deferred here
- [ADR-0005](0005-hub-only-firewall.md), [ADR-0011](0011-process-privilege-model.md) — existing trust-boundary precedent (tunnel-authenticated peers as the enforcement boundary), reused for T-017's mitigation
- `SettingsView.js:874-876` — current `wgClientDns` freetext field this ADR's Settings changes build on
- RFC 1035 — Domain Names, Implementation and Specification (message format, label compression)
