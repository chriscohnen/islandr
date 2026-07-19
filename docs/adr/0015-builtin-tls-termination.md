# ADR-0015 — Built-in TLS termination (no mandatory reverse proxy)

**Status:** Accepted
**Date:** 2026-07-19
**Deciders:** Christian Cohnen
**Target release:** 0.14.0 or later (first ADR-gated implementation slice; not bundled into 0.13.0)

## Context

Today islandr serves plain HTTP. Every deployment guide points operators at a reverse proxy (Caddy, Traefik, nginx) or an edge/CDN provider to terminate TLS — see README's tech-stack table (`TLS | Caddy or Let's Encrypt at the edge`). For islandr's target audience (PRD §3 — Felix the sysadmin running a 30–80 person company, and the homelab operator), that proxy is the **last mandatory external moving part** in an otherwise self-contained deployment (systemd unit or Docker image + optional socket proxy, ADR-0011/0012). Many of these operators already sit behind a CDN/DDoS provider (Cloudflare, CloudFront, Fastly, Azure Front Door, Akamai) that hands out an edge/origin certificate for free — for them, running a second TLS-terminating process purely to hold that certificate is pure ceremony.

Issue #22 asks: can islandr terminate HTTPS itself, with a certificate importable from any provider (not just Cloudflare), swappable at runtime without a restart?

### Provider-neutrality

Cloudflare is the worked example in the issue, not the assumption. The design below treats "import a cert + key" and "reference a file path" as the only two primitives — nothing is Cloudflare-specific in code, config, or copy. AWS CloudFront + ACM, Fastly, Azure Front Door, Akamai, Google Cloud CDN/Armor, or a Let's Encrypt cert obtained by any external tool all flow through the same two import modes.

### The runtime-reload mechanism, and where the filesystem still matters

Quarkus's modern TLS layer (`quarkus.tls.*`, the "TLS registry") is built for exactly this: a `TlsConfiguration` can be reloaded at runtime — `reload()` refreshes the keystore/truststore, and firing a `io.quarkus.tls.CertificateUpdatedEvent` tells the running Vert.x HTTP server to call `updateSSLOptions()` with the new material. No restart, no dropped connections. This is materially different from (and replaces) the older `quarkus.http.ssl.certificate.*` properties, several of which are build-time-fixed and native-image-baked — unusable for this feature.

Quarkus's **built-in** reload mechanism only watches the **filesystem** — it re-reads a keystore file on a poll interval. That's the right fit for the "reference a file path" import mode (operator's own ACME client or CDN tooling manages the file; islandr never touches or copies it — same trust boundary Caddy/nginx already have today). It is the *wrong* fit for the "islandr holds the material" import mode, where forcing a filesystem round-trip would mean writing the private key to disk on every certificate change — a needless increase in the file-based attack surface for a value that only needs to exist in memory once decrypted.

For that mode, Quarkus exposes `io.quarkus.tls.KeyStoreProvider` — a CDI bean Quarkus calls whenever a named `TlsConfiguration` is created *or reloaded*. An islandr-authored `KeyStoreProvider` builds a `java.security.KeyStore` **in memory** from the current DB row (decrypting the private key only for the duration of that call) and hands it back; nothing is ever written to disk. Saving new certificate material in Settings triggers the same `reload()` + `CertificateUpdatedEvent` pair used by the file-reference mode — from the outside, both modes look identical (instant, no restart); only the source of the bytes differs.

This mirrors a pattern islandr already relies on elsewhere: the database is the source of truth and the single thing an admin edits (ADR-0008), and a *reload* primitive — not a restart — is how a change takes effect (the nftables ruleset is recomputed and atomically reloaded via `nft -f`, ADR-0003/0012, on every ACL change). TLS certificate material gets the same shape of answer, adapted to where the material physically needs to live.

### Private key at rest

islandr already has a working answer for "a secret the app itself needs to keep functioning across restarts, not a client artifact it can choose to forget": `EncryptionService` (AES-256-GCM, `enc$`-prefixed, key delivered via systemd-creds or an env var, ADR-0007) already encrypts the Google Workspace service-account JSON unconditionally when a key is configured (`SettingsService.updateGoogleWorkspace`). The TLS private key is the same category of secret — islandr needs it present and decryptable to keep serving HTTPS, so ADR-0007's *peer*-key model (`never` / `plaintext` / `encrypted`, with a real "never persist" option) doesn't fit: there is no meaningful "never" mode for a certificate islandr itself terminates TLS with. The Google-Workspace-JSON precedent is the correct fit and is reused as-is: encrypted at rest when `EncryptionService.isConfigured()`, plaintext with an explicit admin-facing warning banner otherwise (identical banner language pattern to ADR-0007's `plaintext` retention mode).

### ACME / Let's Encrypt

The issue explicitly sequences this as a **second step** ("needs port 80 briefly reachable from the internet"). Exposing port 80, running an ACME client, and scheduling renewal is materially more code and a materially different threat model (unauthenticated inbound HTTP validation traffic) than importing material an operator already has. Bundling it into this ADR would conflate two decisions with different risk profiles. This ADR's scope is manual import (either storage mode below); ACME is deferred to a follow-up issue once this ships and the reload mechanism is proven in production.

## Decision

Built-in HTTPS via Quarkus's TLS registry, with **two operator-chosen certificate storage modes** — mirroring the DB-first-with-escape-hatch shape islandr already uses for private-key retention (ADR-0007) and runtime settings (ADR-0008):

1. **Managed (DB-stored).** Admin uploads a `.p12` or a PEM cert + private key via Settings. The certificate and (encrypted-when-configured) private key are stored in the `settings` table. An islandr-authored `KeyStoreProvider` CDI bean builds the `KeyStore` in memory on every `TlsConfiguration` creation/reload — the private key is decrypted only transiently, for the duration of that call, and is never written to disk. Saving new material calls `TlsConfiguration.reload()` and fires `CertificateUpdatedEvent` immediately — no restart, no wait for a poll interval.

2. **Referenced (file path).** Admin points Settings at a filesystem path islandr does not manage (e.g. mounted by the operator's own ACME client or a CDN's origin-cert delivery tooling — a Docker secret, a bind mount). islandr configures a named `quarkus.tls.<name>.key-store.path` pointing at it and relies entirely on Quarkus's own `reload-period` file-watch — no custom code, no material ever touches the database. Same trust boundary reverse proxies already have today: the operator's own filesystem permissions are the security boundary.

Both modes replace the same server-facing named TLS configuration, so the running HTTP endpoint doesn't know or care which mode is active — switching between them is itself just another reload.

**Not in this ADR's scope:** ACME/Let's Encrypt auto-provisioning (tracked as a follow-up issue once this ships).

## Alternatives considered (Pugh Matrix)

Baseline: **Built-in TLS via Quarkus TLS registry, dual storage mode** (the decision). +1 better, 0 equal, −1 worse.

| Criterion (weight)                                            | Built-in TLS (baseline) | Reverse proxy required (status quo) | Tunnel sidecar (e.g. Cloudflare Tunnel) | Built-in ACME | Classic `quarkus.http.ssl.*` (build-time-fixed) |
| --------------------------------------------------------------- | ------------------------ | ------------------------------------ | ---------------------------------------- | -------------- | -------------------------------------------------- |
| Zero mandatory external process for TLS (6)                     | 0                         | −1                                    | −1                                        | +1              | 0                                                    |
| No restart needed to rotate a certificate (5)                   | 0                         | +1 *(proxy already solves this)*      | +1                                        | 0               | −1 *(the exact problem this ADR solves)*            |
| Provider-neutral (any CDN/CA cert imports the same way) (4)     | 0                         | +1                                    | −1 *(tunnel products aren't interchangeable across providers)* | −1 *(ACME is CA-specific, and CDN-fronted deployments often can't expose port 80)* | 0 |
| Private-key exposure added to islandr's own process/DB (4)      | 0                         | +1 *(islandr never touches it)*       | +1                                        | 0               | 0                                                    |
| Native-image compatibility / build complexity (3)                | 0                         | +1 *(no islandr TLS code at all)*     | +1                                        | ? *(unverified ACME-client native compat)* | +1 *(simpler mechanism, but disqualified above)* |
| Implementation + new attack-surface cost (3)                    | 0                         | +1                                    | +1                                        | −1              | +1                                                   |
| **Weighted total**                                               | **0**                    | **12**                               | **9**                                     | **−2**          | **−2**                                               |

Notes:

- **Reverse proxy (status quo)** and **tunnel sidecar** both score higher than the baseline on pure engineering-conservatism criteria — unsurprising, since "islandr never touches a TLS secret" is always going to score well on exposure and complexity in isolation. That is the same shape of result ADR-0012 found for "systemd only" (its highest-scoring alternative, chosen against for Docker support): the numeric winner doesn't satisfy the hard requirement driving the ADR. Here that requirement — stated directly in issue #22 — is that the reverse proxy stop being *mandatory* for a TLS-terminated deployment. Both alternatives fail that requirement by construction (they *are* the mandatory external process), so neither is viable regardless of score. They remain excellent, and already-supported, *optional* paths for operators who prefer them — nothing in this ADR removes the ability to run islandr behind Caddy/Traefik/nginx or a tunnel; it adds a path that doesn't require it.
- **Built-in ACME** scores worst on provider-neutrality and cost, and carries an unverified native-image compatibility question (`?`) for whichever ACME client library would be used — consistent with the issue's own sequencing (second step, not this ADR).
- **Classic `quarkus.http.ssl.*`** is disqualified outright on the one criterion that matters most (no-restart), which is the literal problem statement; it is included only to show the newer TLS registry is a strict improvement, not a style preference.

## Consequences

- Settings gains a TLS section: storage mode (managed / referenced), certificate upload or path input, and a certificate-expiry-date display (surfaced from the parsed cert — a Chapter 6 runtime scenario worth adding once implemented: "certificate expires in N days" warning banner, since ACME auto-renewal is out of scope here).
- A DB migration adds the certificate/key columns (managed mode) and the path field (referenced mode) to `settings`, following the existing runtime-settings pattern (ADR-0008).
- `EncryptionService` gains a second consumer beyond peer private keys and the Google Workspace service-account JSON; no change to the service itself.
- A new `KeyStoreProvider` CDI bean is islandr-authored code at a security boundary (parses attacker-controllable — admin-supplied — PKCS12/PEM material). It must reject malformed or mismatched cert/key pairs with a clear error before ever calling `reload()`, so a bad upload can't take the HTTPS endpoint down.
- **R-150** — The TLS private key, once decrypted for a `KeyStoreProvider` call, exists in JVM heap for the duration of that call. Mitigation: encrypted at rest when `EncryptionService` is configured (same guarantee as ADR-0007 `encrypted` mode); the decrypted value is not cached or retained beyond building the `KeyStore` object.
- **R-151** — In *referenced* mode, islandr does not control the filesystem permissions of the path it's pointed at. Mitigation: Settings must document that the referenced path's ownership/mode is the operator's responsibility, mirroring the existing expectation for a reverse-proxy's own certificate file.
- **R-152** — An admin-supplied malformed or self-signed-by-mistake certificate could be accepted and pushed live, breaking HTTPS for every client. Mitigation: validate the cert/key pairing and basic X.509 sanity (not expired, key usage includes server auth) before calling `reload()`; keep serving the previous working configuration on validation failure.
- **R-153** — No renewal reminder means a certificate can silently expire (managed mode) if the admin forgets. Mitigation: the expiry-date banner named above; full auto-renewal is out of scope until the ACME follow-up.

## References

- [ADR-0007](0007-private-key-retention.md) — private-key retention model and `EncryptionService`, reused here for the managed mode's private key
- [ADR-0008](0008-runtime-settings-in-db.md) — DB-first runtime settings, the pattern this ADR follows for certificate storage
- [ADR-0012](0012-docker-socket-proxy.md) — the reload-not-restart precedent (nftables ruleset), and the Pugh-matrix style this ADR follows
- [Quarkus TLS registry reference](https://quarkus.io/guides/tls-registry-reference) — `reload()`, `CertificateUpdatedEvent`, `KeyStoreProvider`
