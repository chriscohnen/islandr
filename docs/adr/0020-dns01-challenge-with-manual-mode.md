# ADR-0020 — DNS-01 challenge support, with a manual no-API-token mode alongside Cloudflare

**Status:** Accepted (retroactive — written after implementation; see Context)
**Date:** 2026-07-26
**Deciders:** Christian Cohnen
**Target release:** 0.15.0
**Relates to:** [Issue #41](https://github.com/chriscohnen/islandr/issues/41), [ADR-0019](0019-acme-hand-rolled-client.md) (the hand-rolled ACME client this extends)

## Context

**Note on process:** this ADR was written after `AcmeService`/`CloudflareDnsProvider`/the DNS-01 flow were already implemented and shipped (commits `59f1204`, `204f93a` on `release/0.15.0`) — the code's own comments cite "ADR-0020" throughout (`AcmeService.java`, `Settings.java`, `SettingsResource.java`, `V49__acme_dns01.sql`, etc.), but the file itself was never created during that session. This ADR reconstructs the decision record the code already assumes exists, so the citations resolve to something real. Treat "Accepted" here as "already in effect," not as a decision still open for debate.

[ADR-0019](0019-acme-hand-rolled-client.md) shipped ACME issuance via HTTP-01 only: Let's Encrypt fetches a token from `GET /.well-known/acme-challenge/{token}` on the hub, which requires port 80 reachable from the internet. Issue #41 asks for DNS-01 as an alternative — proving domain ownership via a `TXT _acme-challenge.<domain>` record instead — for hubs that deliberately keep port 80 closed (e.g. behind a restrictive perimeter firewall, or simply as a smaller attack surface).

DNS-01 needs a way to create/delete that TXT record. The obvious default is a DNS provider API (this ADR picks Cloudflare first, per the AskUserQuestion answer at implementation time — widely used, free tier, well-documented API). But not every operator's domain is on a provider with a scriptable API, and requiring one would exclude anyone on Hetzner, IONOS, a registrar's own basic DNS, or dealing with a domain managed by someone else entirely.

Mid-implementation, a second requirement was added: **DNS-01 without any API integration at all** — the same "show, don't automate" pattern islandr already uses for the wg0 bootstrap commands (issue #40): pause issuance, display the record name/value the admin needs, let them add it however they manage DNS, then resume on request. This turns a single-path feature (Cloudflare-only DNS-01) into two provider modes sharing one challenge-response core.

## Decision

**Two `dnsProvider` modes under one `challengeType: "dns-01"`:**

- **`cloudflare`** — `CloudflareDnsProvider` (`acme/dns/CloudflareDnsProvider.java`) creates and deletes the TXT record via the Cloudflare v4 API, using a per-instance API token (`Zone:DNS:Edit` scope). Zone lookup walks up the label chain (`GET /zones?name=X`, then peeling the leftmost label until a zone matches) since Cloudflare's zone list endpoint is exact-match only and the domain being issued for is frequently a subdomain of the zone (`vpn.example.com` → zone `example.com`).
- **`manual`** — no API integration whatsoever. `AcmeService.respondToManualDns01Challenge` computes the TXT digest (RFC 8555 §8.4: `base64url(SHA-256(keyAuthorization))`), persists it plus the in-flight order/authz/challenge/finalize URLs on `Settings` (`acmeDnsPending*` columns), and returns *without* responding to the challenge or advancing the ACME order. The admin adds the TXT record themselves, then calls `POST /api/v1/settings/acme/dns-continue`, which re-establishes an `AcmeClient` against the stored account and finishes the exchange (`AcmeService.continueManualDnsChallenge`).

Both modes share the same digest computation, challenge-lookup (`findChallenge`), and order-finalization code (`finalizeAndDownload`) — only how the TXT record gets published differs.

**Fixed propagation wait, not active DNS polling.** After publishing the record (Cloudflare mode) or before the admin confirms (manual mode, implicitly — the admin controls the timing), the client waits `islandr.acme.dns-propagation-wait` (default 30 s) before notifying Let's Encrypt, rather than polling DNS resolution directly. Actively resolving the TXT record from Java would mean either shelling out to `dig`/`host` (a new external-process dependency, mirroring the `wg`/`nft` pattern this codebase already uses sparingly and deliberately) or using the JDK's internal `com.sun.jndi.dns.DnsContextFactory` — an unsupported, module-system-fragile API that risks a GraalVM native-image reflection/module problem (the same class of risk flagged and avoided in [ADR-0019](0019-acme-hand-rolled-client.md)'s own native-image risk assessment). A fixed wait is strictly simpler and avoids that risk entirely, at the cost of not adapting to actual propagation speed.

**Validation happens before any network call.** `AcmeService.validateDnsConfig` rejects an unsupported provider or a missing Cloudflare token immediately after loading `DnsChallengeConfig`, before directory discovery, account lookup, or order creation. This was a fix made during implementation (a test originally expected the "no token" error but got a 404 from the unstubbed fake ACME server instead, because the token check lived deep inside `respondToDns01Challenge`) — it is also simply better behavior: a config-shaped mistake fails fast rather than after several round-trips to Let's Encrypt.

**Settings "keep existing value if omitted" semantics** apply to `challengeType`/`dnsProvider`/`dnsApiToken` on `PUT /api/v1/settings/acme`, matching the existing pattern for re-submitting a form without re-entering a secret (the API token is never echoed back in the response).

## Alternatives considered (Pugh Matrix)

Baseline: **A — Cloudflare API + manual mode, fixed propagation wait** (the decision).

| Criterion (weight) | A: Cloudflare + manual, fixed wait (baseline) | B: Cloudflare-only (no manual mode) | C: generic multi-provider plugin interface (Hetzner, IONOS, ... from day one) | D: active DNS polling instead of fixed wait |
|---|:---:|:---:|:---:|:---:|
| Works for any DNS provider, not just Cloudflare (5) | 0 | −1 *(excludes every operator not on Cloudflare)* | +1 *(if actually built for N providers)* | 0 |
| Implementation effort (4) | 0 | +1 *(one provider, no pending-state machinery)* | −1 *(N provider adapters, credential storage per provider)* | −1 *(dig/host process or JNDI DNS internals)* |
| Native-image / dependency risk (4) | 0 | 0 | 0 | −1 *(JNDI DNS is unsupported/module-fragile; shelling to dig adds a new external-process dependency)* |
| Matches existing "show, don't automate" precedent (#40) (3) | +1 | −1 | 0 | 0 |
| Time-to-issuance (2) | 0 *(fixed 30 s wait regardless of actual propagation)* | 0 | 0 | +1 *(resumes as soon as DNS actually propagates)* |
| **Weighted total** | **0** | **−7** | **−1** | **−6** |

- **B (Cloudflare-only)** is the smaller, faster-to-ship option, and was in fact the original scope before the manual-mode idea came up mid-implementation. It loses on the criterion that matters most here (weight 5): it silently excludes every operator not on Cloudflare, which is most self-hosters on a registrar's basic DNS. Rejected once the near-zero-cost manual alternative was identified — the shared challenge-response core made adding it cheap rather than a second parallel implementation.
- **C (generic multi-provider plugin interface, Hetzner/IONOS/etc. from day one)** is the "textbook-correct" answer for a *multi-provider* feature, but each additional provider is its own API, its own credential shape, and its own zone-lookup quirks — real, non-speculative scope for providers with zero requester demand yet. The manual mode already covers "any provider" at near-zero marginal cost; a generic Cloudflare-shaped interface (`DnsProvider`) exists in the code (`acme/dns/DnsProvider.java`) so a second automated provider is addable later without restructuring, but building N providers speculatively now was rejected the same way ADR-0019 deferred EAB/ZeroSSL rather than building it un-asked-for.
- **D (active DNS polling instead of a fixed wait)** scores well on user-facing latency but loses on the two highest-weighted technical criteria: it needs either a new external-process dependency (`dig`/`host`) or an unsupported JDK-internal API with a real native-image risk, for a benefit (faster resumption) that a 30 s fixed wait already delivers acceptably for an admin-driven, once-per-cert-lifetime action.

## Consequences

- `AcmeService` gains a `DnsChallengeConfig`/pending-state model (`AcmeSettingsStore.DnsChallengeConfig`, `PendingManualDns`) alongside the existing HTTP-01 path — the largest single addition to the `acme` package since ADR-0019.
- `Settings` gains 10 new columns (`acme_challenge_type`, `acme_dns_provider`, `acme_dns_api_token`, 6 `acme_dns_pending_*` columns for the manual mode's cross-request state) via `V49__acme_dns01.sql`.
- A new unauthenticated-adjacent admin endpoint, `POST /api/v1/settings/acme/dns-continue`, resumes a paused manual challenge — same "block up to ~60s, surface `acmeLastError` on failure, 200 not 5xx" pattern as `enableAcme` already established.
- **R-167** — The Cloudflare API token is a new secret category, stored encrypted-at-rest the same way as `tlsKeyPem` ([ADR-0007](0007-private-key-retention.md)'s pattern), but scoped to a third-party API rather than islandr's own crypto material — a leaked token grants DNS-write access to the operator's zone, not just islandr. Mitigation: token is never echoed back in any API response; the field hint in the UI documents the minimal required scope (`Zone:DNS:Edit`), not a broader one.
- **R-168** — Manual-mode pending state (order/authz/challenge/finalize URLs) is held in the `Settings` singleton row indefinitely if the admin never returns to click "continue" — there is no timeout or expiry on a stale pending challenge. Mitigation: none implemented yet; a stale pending challenge is harmless (Let's Encrypt's own authorization objects expire server-side, so `continueManualDnsChallenge` would simply fail with an ACME-side error and the admin can re-run `enableAcme` to start over) but this is not yet surfaced as a distinct, actionable error message. Flagged for follow-up, not blocking.
- Follow-up work, deliberately out of this ADR's scope: additional automated providers (Hetzner, IONOS, ...) via the existing `DnsProvider` interface, only if requested; a distinct "your pending DNS challenge expired, start over" error message for R-168.

## References

- [Issue #41](https://github.com/chriscohnen/islandr/issues/41) — the feature this ADR backs.
- [ADR-0019](0019-acme-hand-rolled-client.md) — the hand-rolled ACME client this extends, and the native-image risk-avoidance precedent this ADR's fixed-wait decision follows.
- [ADR-0007](0007-private-key-retention.md) — the encrypted-at-rest secret pattern applied to the Cloudflare API token.
- Issue #40 (wg0 bootstrap commands) — the "show, don't automate" precedent the manual mode follows.
- `acme/dns/DnsProvider.java`, `acme/dns/CloudflareDnsProvider.java`, `acme/AcmeService.java` — the implementation.
- [RFC 8555 §8.4](https://www.rfc-editor.org/rfc/rfc8555#section-8.4) — DNS-01 challenge specification.
