# ADR-0028 — WebAuthn for the recovery admin: Vert.x auth as an engine, Islandr keeps the login flow

**Status:** Accepted (backend implemented in 0.23.0, console UI shipped in 1.0.0, issue #67)
**Date:** 2026-09-05
**Deciders:** Christian Cohnen
**Target release:** none yet — [#67](https://github.com/chriscohnen/islandr/issues/67) is filed but unscoped; this ADR decides only *how* WebAuthn would be integrated, so the scope decision is not made twice.
**Relates to:** [ADR-0001](0001-quarkus-backend.md) (Quarkus + GraalVM native image, the constraint that rules out reflection-heavy stacks), [ADR-0002](0002-vue-without-npm.md) (no frontend build step — WebAuthn is browser-native, so this holds), [ADR-0023](0023-resource-dns-resolver-hand-rolled.md) (precedent: use a library as an engine, hand-roll the surface), [ADR-0026](0026-external-api-facade.md) (the other authentication surface, deliberately kept parallel rather than merged)

## Context

Islandr's GUI login has two paths: OIDC, and a local recovery admin account authenticated by `ISLANDR_ADMIN_USER`/`ISLANDR_ADMIN_PASSWORD`. OIDC logins inherit the identity provider's own MFA policy before the user ever reaches Islandr. The recovery admin inherits nothing — it is a password and nothing else, on the highest-privilege account in the system. That is the gap [#67](https://github.com/chriscohnen/islandr/issues/67) describes.

Two constraints narrow the library choice before any of them is compared:

- **GraalVM native image (ADR-0001).** A library that needs aggressive runtime reflection, bytecode generation, or JNI bindings either fails to compile into a native image or needs hand-maintained `reflect-config.json` that silently rots between releases.
- **No frontend build step (ADR-0002).** This one turns out not to discriminate: WebAuthn is reached through the browser's own `navigator.credentials` API, so every option below is implemented in the frontend as plain ES modules with no npm dependency. It is recorded because it is the constraint an outside recommendation would expect to be decisive, and it is not.

### Why this account specifically

The recovery admin is not "the login for people without OIDC". It is a **break-glass account**, and each of its three defining properties argues for a second factor rather than against one:

- **It exists to be independent of the IdP.** If Entra ID or Google Workspace is down, or Islandr's own OIDC configuration is wrong, this account is the only remaining way in. Its value comes precisely from not depending on the provider — which is also why the provider's MFA policy cannot protect it.
- **That independence is currently also an MFA bypass.** Every org-enforced Conditional Access rule stops at the OIDC path. A single static password from an environment variable guards the account that can rewrite every ACL and every peer on the hub. The stronger the org's OIDC policy, the more attractive the account that sidesteps it.
- **It should not be tied to one person.** Break-glass credentials outlive the admin who set them up. Rotating `ISLANDR_ADMIN_PASSWORD` on offboarding is straightforward; a *physical* second factor is not, unless several can be registered at once. With multiple credentials the successor enrols their own authenticator during handover and the predecessor's is removed afterwards — a clean revocation with no window in which nobody can get in.

A third constraint is specific to this codebase and is decisive.

**Islandr owns its authentication surface end to end.** There is no `quarkus-security` dependency, no `quarkus-oidc`, no `@RolesAllowed`. Sessions are a JPA `Session` row behind the `islandr_session` cookie; `SessionFilter` resolves that cookie on every request and `Auth.requireAdmin` enforces at the endpoint. Even OIDC is hand-rolled — `OidcAuthResource` performs the code exchange itself and then mints an ordinary Islandr session.

That single chokepoint is load-bearing, and recently so. Two security fixes in 0.20.0 exist precisely because a session must be re-evaluated rather than trusted for its lifetime: `SessionFilter.resolveAuth` re-reads `is_admin` and `User.accessAllowedAt` on *every* request, so a disabled account or an expired access window stops working immediately instead of at the next login (issue [#53](https://github.com/chriscohnen/islandr/issues/53)). Anything that authenticates a request without passing through that filter is outside a control the project deliberately added weeks ago.

## Decision

Use **`io.vertx:vertx-auth-webauthn`** as the WebAuthn *engine* — challenge generation, CBOR decoding, attestation-format handling, signature and counter verification — and keep registration, authentication and session issuance inside Islandr's own `auth` package, ending in the same `Session` row and `islandr_session` cookie every other login produces.

Concretely: `WebAuthnResource` under `/api/v1/auth/...` exposes register-challenge, register-verify, login-challenge and login-verify; a `WebAuthnCredential` entity stores credential id, public key and signature counter; a successful assertion calls the existing `SessionService` rather than a parallel one.

Two details of the store follow from what this account is. **Several credentials are registrable**, because a single one makes handover impossible without a gap in coverage. And the store is scoped to *the local recovery admin* as a singleton, **not keyed by the configured username** — the ENV admin has no row in `users` (`Session.userId` is null for it, identity is the `principal` string), so keying on that string would mean renaming `ISLANDR_ADMIN_USER` silently orphans every registered authenticator. A rename is a configuration change; it must not be an authentication event.

Vert.x is not a new dependency. Quarkus runs on it, and `io.vertx:vertx-core:4.5.22` is already on the runtime classpath — `vertx-auth-webauthn` is published on the same line and inherits the Vert.x ecosystem's GraalVM configuration.

**This deliberately rejects `quarkus-security-webauthn`**, which is the obvious first answer and is genuinely available for the pinned platform (`io.quarkus:quarkus-security-webauthn:3.29.4` resolves). It is rejected for one reason, not for quality: the extension registers its own `HttpAuthenticationMechanism` and manages **its own session cookie** (`quarkus-credential`) with its own `quarkus.webauthn.session-timeout`. Adopting it means a second authenticated-session mechanism next to `SessionFilter` — one that does not re-read `is_admin`, does not consult `accessAllowedAt`, and therefore does not inherit the #53 fix. Reconciling that is more work than the boilerplate the extension saves, and the failure mode if it is reconciled imperfectly is an admin session that outlives the admin's access. The extension's value is highest for an application that has no auth stack yet; Islandr's cost is highest for exactly that reason.

## Alternatives considered (Pugh Matrix)

Baseline: **A — `vertx-auth-webauthn` as engine, Islandr owns endpoints and session** (the decision).

| Criterion (weight) | A: Vert.x engine, own flow | B: `quarkus-security-webauthn` | C: `com.yubico:webauthn-server-core` | D: `webauthn4j` | E: TOTP instead of WebAuthn | F: nothing (status quo) |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| One session/authorization path — everything passes `SessionFilter` (5) | 0 | -1 | 0 | 0 | 0 | 0 |
| Closes the MFA gap on the highest-blast-radius account (5) | 0 | 0 | 0 | 0 | 0 | -1 |
| Native image without hand-maintained reflection config (4) | 0 | 0 | -1 | -1 | 0 | 0 |
| No npm / no frontend build step (4) | 0 | 0 | 0 | 0 | 0 | 0 |
| Phishing resistance (origin-bound credential) (3) | 0 | 0 | 0 | 0 | -1 | -1 |
| Implementation cost for the recovery-admin slice (3) | 0 | +1 | -1 | -1 | +1 | +1 |
| Insulated from upstream auth-API churn (2) | 0 | +1 | +1 | +1 | +1 | +1 |
| Consistent with the hand-rolled auth surface (ADR-0023 precedent) (2) | 0 | -1 | 0 | 0 | 0 | 0 |
| **Weighted total** | **0** | **−2** | **−5** | **−5** | **+2** | **−4** |

**E scores above the baseline and is still not the decision.** That is not a defect in the matrix; it is the matrix doing its job. TOTP is genuinely cheaper and #67 names it as an acceptable smaller first step. It loses only on phishing resistance — a TOTP code can be relayed to a lookalike origin, a WebAuthn assertion cannot, because the credential is cryptographically bound to the origin. For the account that can rewrite every ACL and every peer on the hub, that one property is the reason the feature was filed at all, and the decision accepts a higher cost to keep it. An implementer who reverses that judgement should record it here rather than silently shipping TOTP.

C and D are both capable and standards-correct. They lose on the native-image criterion for the same reason: their CBOR/JSON and cryptography dependencies (Jackson, BouncyCastle) need reflection configuration that Islandr would then own and have to keep working across releases, and `webauthn4j` brings an enterprise feature surface far beyond one credential on one account.

## Implementation note (2026-09-19, 0.23.0)

The backend is built: `WebAuthnCredential` + `V79`, `WebAuthnCredentialStore`,
`WebAuthnChallenges`, `WebAuthnService` over `io.vertx:vertx-auth-webauthn:4.5.27`
(the 4.5 line this ADR names; the patch version moved with the CVE bumps applied
to `vertx-core`), and `WebAuthnResource` under `/api/v1/auth/webauthn`. A
successful assertion issues a session through `SessionService`, as decided.

Two facts surfaced during implementation that this ADR did not state, and both
constrain the feature rather than the design:

**A relying-party id cannot be an IP address.** A console reached as
`https://10.77.140.1` cannot use security keys at all — there is no registrable
domain to bind a credential to. WebAuthn therefore *depends on* the hub having
a name, which is what the resolver's `hub.<zone>` record (same release) exists
for. Deployments that reach the console by address must give it a name first.

**A credential is bound to the name it was registered under.** A key enrolled
at `hub.islandr.internal` is not offered at `konsole.firma.de`, and the browser
gives no reason for it. The relying-party id is therefore stored with each
credential, and the API distinguishes "a key is registered" from "a key is
usable from here" so the console can explain what the browser will not.

**Not covered by automated tests:** the cryptographic half — a browser
producing an attestation or assertion this server accepts. That is the engine's
job and needs a real authenticator; the tests cover challenge issuance, the
counter rule, the relying-party binding and every refusal path.

## Consequences

**Islandr owns the WebAuthn protocol surface.** Registration and assertion endpoints, the credential store and the ceremony's state handling are application code, reviewed like any other. That is the same trade ADR-0023 made for the DNS wire format: more code here, no framework in the request path.

**A Vert.x 5 platform upgrade forces a migration** — creates **R-187**. `vertx-auth-webauthn` is superseded by `vertx-auth-webauthn4j` in the Vert.x 5 line, and the rename is not source-compatible. Choosing the extension would have let Quarkus absorb that migration; choosing the engine directly means Islandr performs it. Bounded — the call sites are the four endpoints above — but real, and it lands whenever the pinned platform moves.

> **Update 2026-09-19:** the platform moved, and this did *not* fire. Islandr went from Quarkus 3.29.4 to the 3.33 LTS line, whose BOM still resolves Vert.x **4.5.31** and still ships `vertx-auth-webauthn`. R-187 stays open and unchanged — it is a question of *when* Quarkus adopts Vert.x 5, not of this upgrade.

**Enforcing a second factor on the break-glass account can lock the operator out** — creates **R-188**, and the design answers it rather than deferring it. Registration *is* the opt-in: with no credential registered the account behaves exactly as today. (This paragraph originally assumed the key would become *required* once registered, step-up style, after the password. The console UI decision below settled that question the other way — a key is an *alternative* sign-in, not an added requirement — which removes the lockout risk this paragraph was written to answer, rather than merely mitigating it.) Recovery is an offline path — a CLI command on the hub that clears the registered credentials — so losing every authenticator costs a shell session, not the instance.

> **Update 2026-09-20 — built, and not as a CLI command.** The binary is a
> server with no command mode, so introducing one for this would mean a second
> entry point and, in a container, a process nobody starts. The reset is an
> environment variable read at startup instead: `ISLANDR_WEBAUTHN_RESET=true`,
> restart, unset. Every other operational switch on this product already works
> that way, so an operator learns nothing new, and it behaves identically for
> the native binary and the image. `WebAuthnReset` in the `auth` package, with
> the audit entry this paragraph requires. The hazard the variable adds —
> leaving it set wipes freshly enrolled keys on every restart — is answered by
> warning on *every* boot while it is set, not only the boot that did the work.
> Documented in `docs/install.md`. **R-188 is mitigated in fact, not only in
> design.**

That offline reset is deliberately not treated as a backdoor, because it does not widen the trust boundary: anyone who can run it already has shell access on the hub, and with it the database, the environment file holding `ISLANDR_ADMIN_PASSWORD`, and the ability to restart the service. It must, however, be audit-logged like any other credential change — the operator who resets it is not necessarily the one who notices afterwards.

**A new credential store becomes a target** — ties **T-021** (§8.1), mitigation in §8.2. WebAuthn public keys are not secrets, but the signature counter is integrity-relevant: a store that accepts a non-increasing counter without notice loses the clone-detection property the standard provides.

**Nothing changes for OIDC users.** The extension's per-request mechanism would have applied application-wide; an endpoint-scoped flow does not touch a path it is not on.

> **Update 2026-09-22 — the console UI shipped, offered alongside the
> password.** `webauthn-backend` (0.23.0) left the endpoints unused; three
> options were on the table for how the login screen would use them —
> alongside the password, replacing it, or requiring both as a genuine second
> factor. Decided **alongside**: a "sign in with a security key" button
> appears next to the local-admin password form once a usable credential is
> registered — `availability` already told the login screen apart the two
> ways a key can fail to help (none registered, or registered for a
> different hostname); the password stays a complete, unrestricted path on
> its own. Registration and removal live in Settings next to the other
> account-level switches.
>
> Chosen for cost, not for safety margin: it adds a login option without
> making a claim about the account's security *level* that a later
> tightening would have to walk back. Whoever wants the stronger property —
> replacing the password, or requiring both — can still build it once the
> offline reset (R-188) has run on a real hub, not before; starting there
> and finding operators lock themselves out would have been the expensive
> order.
>
> **R-188 changes from mitigated to effectively closed by this decision**,
> not by the offline reset: since a security key is never the *only* way in,
> losing one cannot lock anyone out on its own — the reset above still
> matters, but only for the compound case of a lost authenticator on top of
> a forgotten password.

## References

- [#67](https://github.com/chriscohnen/islandr/issues/67) — the issue this decides the integration shape for
- W3C Web Authentication Level 2 — the origin-binding property the phishing-resistance criterion rests on
- Quarkus Security WebAuthn guide — source for the extension's `WebAuthnUserProvider` requirement and its own cookie/session handling
- `SessionFilter`, `SessionService`, `OidcAuthResource` (`src/main/java/de/chriscohnen/islandr/auth/`) — the chokepoint and the hand-rolled-OIDC precedent this decision preserves
