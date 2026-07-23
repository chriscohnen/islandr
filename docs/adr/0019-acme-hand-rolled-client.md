# ADR-0019 — ACME (Let's Encrypt) auto-provisioning via a hand-rolled client, not a library

**Status:** Accepted
**Date:** 2026-07-21
**Deciders:** Christian Cohnen
**Target release:** 0.14.0 or later
**Supersedes:** the ACME follow-up deferred by [ADR-0015](0015-builtin-tls-termination.md)

## Context

[ADR-0015](0015-builtin-tls-termination.md) shipped built-in TLS termination with two manual certificate-import modes (DB-managed, file-path-referenced) and explicitly deferred ACME auto-provisioning to a follow-up (issue #30), for two stated reasons: exposing port 80 for HTTP-01 validation is a different threat-model surface than importing material an operator already holds, and "whichever ACME client library would be used" carried an unverified GraalVM native-image compatibility question — scored `?` in that ADR's own Pugh matrix.

### Library research

`acme4j` (shred/acme4j) is the natural default choice for a Java ACME client: actively maintained (v5.1.0, 2026-04-06), 591 stars, and it already supports External Account Binding (EAB) — what ZeroSSL needs alongside Let's Encrypt, per issue #30's proposal.

Checking the specific unknown ADR-0015 flagged: acme4j's two real dependencies, `jose4j` (JWS/JOSE) and `bcpkix-jdk18on` (Bouncy Castle, PKIX), both have published entries in Oracle's official [`graalvm-reachability-metadata`](https://github.com/oracle/graalvm-reachability-metadata) repository — the same mechanism Quarkus's native build already consumes automatically for other dependencies. That meaningfully de-risks the native-image question at the dependency level. acme4j itself ships no reachability metadata of its own, only a `META-INF/services` ServiceLoader registration for its provider classes (`LetsEncryptAcmeProvider`, etc.) — a well-solved pattern in modern GraalVM/Quarkus native builds. No GitHub issue against acme4j ever mentions GraalVM or native-image, and no public example of acme4j running inside a Quarkus native build was found. **De-risked, not proven** — the honest gap left is an actual native-image build attempt.

### Existing precedent for hand-rolling minimal crypto/DER code

islandr already has a working answer for "do we pull in a general-purpose crypto library for one narrow, well-understood operation": `TlsService.wrapPkcs1RsaKeyAsPkcs8` (added for Cloudflare Origin Certificate import) hand-encodes a minimal DER/PKCS8 envelope — `derEncode`/`derLength` helpers, ~40 lines — instead of adding Bouncy Castle as a dependency just to re-wrap a PKCS1 RSA key. islandr has no Bouncy Castle (or any general ASN.1/crypto library) dependency today.

The full RFC 8555 surface needed for HTTP-01 issuance against Let's Encrypt is narrow enough to fit the same pattern:

- **JWS request signing** — every ACME POST is a signed JWS. Plain JDK `java.security.Signature` with an EC P-256 (ES256) account key covers this; no external JOSE library needed.
- **JSON** — Jackson is already a Quarkus dependency (`rest-jackson` is already an installed feature); no new dependency.
- **HTTP client** — JDK's own `java.net.http.HttpClient`.
- **Base64url** — `java.util.Base64.getUrlEncoder().withoutPadding()`.
- **Protocol state machine** — directory discovery → `newNonce` → `newAccount` → `newOrder` → poll authorizations → HTTP-01 challenge → `finalize` → download certificate chain. This is orchestration code, not cryptography.
- **CSR (PKCS#10)** — the one piece with no existing islandr precedent. The JDK has no public PKCS#10 builder; this needs its own hand-rolled DER encoding (`CertificationRequestInfo`: subject, `SubjectPublicKeyInfo`, `extensionRequest` attribute carrying the SAN), built the same way as the existing PKCS1→PKCS8 wrapper, then signed and framed as the final `CertificationRequest`. Larger than the existing helper (roughly 150–250 lines including the SAN extension), but the same technique, not a new one.

## Decision

**Hand-rolled, dependency-free ACME (RFC 8555) client**, implementing exactly the HTTP-01 + Let's Encrypt subset described above — no `acme4j`, no Bouncy Castle, no new third-party dependency of any kind. This removes the native-image unknown outright instead of merely shrinking it, and keeps every line of a security-sensitive protocol path auditable in islandr's own codebase, consistent with the precedent already set for PKCS1 import.

Concretely:

- The ACME account's own private key (EC P-256) is generated once and persisted like other islandr-managed secrets — through `EncryptionService`, the same AES-256-GCM-at-rest pattern already used for peer private keys ([ADR-0007](0007-private-key-retention.md)) and the TLS private key ([ADR-0015](0015-builtin-tls-termination.md)).
- Obtained certificate/key material becomes a **third source** for the existing `KeyStoreProvider` / `reload()` / `CertificateUpdatedEvent` mechanism from ADR-0015, alongside *managed* (DB-upload) and *referenced* (file-path) — no new hot-reload plumbing, only a new material origin.
- A new unauthenticated endpoint, `GET /.well-known/acme-challenge/{token}`, serves the HTTP-01 key authorization. It is always routable but returns 404 for any token that doesn't match the currently in-flight order's expected value — there is no window where it serves anything but a 404 outside an active issuance attempt.
- **Scheduled renewal**: a daily check renews the ACME-managed certificate once it's within a configurable window (default 30 days) of expiry, plus a check on every boot as a backstop against a missed scheduled run. This closes **R-153** (from ADR-0015: "no renewal reminder … can silently expire") for this mode specifically — the expiry banner from ADR-0015 remains as a second backstop for the other two modes, which stay fully manual.
- **v1 ships Let's Encrypt only.** Provider-neutrality within RFC 8555 is a design goal (nothing Let's-Encrypt-specific in the protocol code — only its directory URL is a default, not a constant), but **EAB/ZeroSSL support is explicitly deferred**, not built speculatively alongside an unproven hand-rolled client. EAB is a small, isolable addition (a CA directory-URL setting, two optional EAB credential fields, one extra signed field at account registration) addable later without restructuring the client, once the Let's Encrypt path is proven in production. This narrows this ADR's scope relative to issue #30's original both-CAs-from-day-one proposal.
- **CDN-fronted deployments that can't expose port 80 to the origin remain out of scope** for this mode, as issue #30 already anticipated — those operators use the existing *referenced* or *managed* modes from ADR-0015 with the CDN's own edge certificate.

## Alternatives considered (Pugh Matrix)

Baseline: **hand-rolled ACME client** (the decision).

| Criterion (weight) | Hand-rolled (baseline) | `acme4j` library | Status quo (ADR-0015 only, no ACME) |
|---|---|---|---|
| Native-image risk (5) | 0 | −1 *(de-risked by dependency metadata, but empirically unverified — see Context)* | +1 *(no ACME code to fail)* |
| New third-party dependency / supply-chain surface (4) | 0 | −1 *(acme4j + jose4j + Bouncy Castle enter the dependency tree)* | +1 *(nothing added)* |
| Implementation cost (3) | −1 *(JWS signing, state machine, and CSR DER encoding all hand-written)* | +1 *(library handles protocol + CSR)* | +1 *(zero code)* |
| Auditability of a security-sensitive protocol path (4) | +1 *(every line is islandr's own, same review bar as existing PKCS1-wrap code)* | 0 *(trusted upstream, but unfamiliar-to-reviewers code in the account-key/signing path)* | +1 *(nothing to audit)* |
| Protocol-evolution maintenance burden (3) | −1 *(islandr owns RFC 8555 compliance going forward)* | +1 *(upstream tracks spec changes)* | +1 *(not applicable)* |
| EAB / ZeroSSL readiness (2) | 0 *(addable later, not built now — see Decision)* | +1 *(already supported today)* | −1 *(never, without building something)* |
| Closes R-153 for at least one storage mode (3) | +1 | +1 | −1 *(status quo — the risk this ADR exists to close)* |
| **Weighted total** | **−1** | **4** | **9** |

Notes:

- **Status quo** wins on every conservatism criterion for the same reason it always does in this document's ADRs (see ADR-0005's and ADR-0015's own matrices for the identical shape of result): not shipping a feature scores well against every risk/cost criterion in isolation. It fails the requirement that motivates this ADR — issue #30 and R-153 both exist because manual-only certificate management is the gap being closed. Included for completeness, not as a live option.
- **`acme4j`** scores highest numerically, largely on implementation cost and maintenance burden — the library genuinely is less code to write and upkeep. It loses on the two criteria this decision actually weighs heaviest: native-image risk remains *unverified in practice* despite the encouraging dependency-metadata finding, and it adds three new packages (`acme4j`, `jose4j`, Bouncy Castle) to the dependency tree of a security-sensitive TLS/crypto path in a codebase that has deliberately avoided that so far. The numeric loss on cost is accepted deliberately, the same way ADR-0015 accepted a nonzero implementation cost over "require a reverse proxy forever."
- The Pugh matrix score does not settle this alone (hand-rolled scores lowest of the three real trade-offs here); the decision rests on the auditability and dependency-surface criteria being weighted correctly for a security-sensitive path in a codebase that has already chosen this trade-off once (PKCS1 import) at a smaller scale.

## Consequences

- A new `AcmeService` (or similarly named class) owns the RFC 8555 state machine, JWS signing, and CSR construction — the largest single addition to the `tls` package since ADR-0015.
- The ACME account private key is a new secret category alongside the TLS certificate private key and peer private keys; `EncryptionService` gains a third consumer, no change to the service itself.
- Settings gains a third TLS mode ("Let's Encrypt (ACME)") alongside managed/referenced, plus a domain field and an issuance/renewal status display (last attempt, next scheduled renewal, last error) — mirroring the enforcement-status pattern already used for the socket proxy.
- A new unauthenticated route exists on the hub for the first time solely to serve ACME challenges — see **T-016** below.
- **R-164** — The HTTP-01 challenge endpoint is a new, permanently-routable, unauthenticated internet-facing surface. Mitigation: it serves only a pre-computed, non-secret token string; no attacker-controlled input is parsed beyond a path segment compared byte-for-byte against the single currently-expected value; returns 404 for everything else, including outside an active issuance attempt. Ties **T-016** (§8.1).
- **R-165** — Hand-rolled JWS signing and PKCS#10 CSR DER encoding are security-sensitive, self-maintained code with no upstream security-patch stream, unlike a library dependency. A bug here could produce a malformed-but-accepted request, or a subtler cryptographic mistake. Mitigation: the protocol surface is deliberately narrow (only what HTTP-01 + Let's Encrypt requires, not the full RFC 8555 surface); the DER-encoding technique reuses the already-reviewed pattern from `TlsService`; integration tests run against Let's Encrypt's **staging** environment (unlimited, non-trust-chained issuance) before any production-CA code path is exercised in CI or documented for operator use.
- **R-166** — A missed scheduled renewal (e.g. the hub is down across the entire 30-day renewal window) could still let an ACME-managed certificate expire, undermining this ADR's own stated goal of closing R-153 for this mode. Mitigation: a renewal check also runs on every boot, not only the daily schedule; ADR-0015's existing expiry-date banner remains a visible backstop for this mode too, not only the manual ones.
- Follow-up work, deliberately out of this ADR's scope: EAB/ZeroSSL support (tracked as a scope addition to issue #30, not a new issue, since the client already accommodates it structurally); a real native-image build/smoke-test of the finished `AcmeService` as part of this feature's own CI, not assumed correct from the dependency-level research alone.

## References

- [ADR-0015](0015-builtin-tls-termination.md) — built-in TLS termination, `KeyStoreProvider`/`reload()` mechanism this ADR plugs into as a third certificate source, and R-153 (closed here for the ACME mode)
- [ADR-0007](0007-private-key-retention.md) — `EncryptionService` and the at-rest secret pattern reused for the ACME account key
- [TlsService.java](../../src/main/java/de/chriscohnen/islandr/tls/TlsService.java) — existing hand-rolled DER/PKCS1→PKCS8 precedent this ADR extends to CSR generation
- [oracle/graalvm-reachability-metadata](https://github.com/oracle/graalvm-reachability-metadata) — reachability metadata for `jose4j` and `bcpkix-jdk18on`, the basis for this ADR's native-image risk assessment of the library alternative
- [RFC 8555](https://www.rfc-editor.org/rfc/rfc8555) — Automatic Certificate Management Environment (ACME)
- Issue #30 — original ACME proposal (both Let's Encrypt and ZeroSSL); this ADR narrows v1 scope to Let's Encrypt only, see Decision
