# 9. Architecture Decisions

All ADRs live in [`docs/adr/`](../adr/). Each includes a Pugh Matrix (–1/0/+1) comparing the chosen option against alternatives.

| ADR | Title | Status | Key trade-off |
|---|---|---|---|
| [ADR-0001](../adr/0001-quarkus-backend.md) | Quarkus as backend framework | Accepted | Fast dev loop + GraalVM native image vs. smaller binary in Go/Rust |
| [ADR-0002](../adr/0002-vue-without-npm.md) | Vue.js without npm | Accepted | Zero-build-toolchain simplicity vs. full bundler ecosystem |
| [ADR-0003](../adr/0003-nftables-replaces-ufw.md) | nftables replaces ufw | Accepted | Programmatic control + atomic reload vs. simpler ufw API |
| [ADR-0004](../adr/0004-sqlite-dev-postgres-prod.md) | SQLite for dev, PostgreSQL for prod | Accepted | One code path for two backends; rejects H2 (durability risk) and embedded Postgres (not actually embedded) |
| [ADR-0005](../adr/0005-hub-only-firewall.md) | Firewall enforcement stays on hub VM | Accepted | Hub compromise blast radius bounded; UCG enforcement deferred to v2 pull-mode agent |
| [ADR-0006](../adr/0006-resource-level-acl.md) | Resource-level ACL with NIST RBAC0 | Accepted | Port-level L4 granularity; explicitly not L7; RBAC1 hierarchy deferred to v2 |
| [ADR-0007](../adr/0007-private-key-retention.md) | Private key retention policy | Accepted (updated 2026-06-08) | `retention=never` default; `plaintext` opt-in for re-display; `encrypted` mode (AES-256-GCM, systemd-creds delivery) implemented in v1 |
| [ADR-0008](../adr/0008-runtime-settings-in-db.md) | Runtime settings in database | Accepted | No restart required for config changes; settings are audited |
| [ADR-0009](../adr/0009-license-eupl-1.2.md) | EUPL-1.2 license | Accepted | EU-governed copyleft, AGPL-compatible, permits commercial use |
| [ADR-0010](../adr/0010-font-and-icon-asset-self-hosting.md) | Font and icon asset self-hosting | Accepted | No CDN calls at runtime; full offline capability |
| [ADR-0011](../adr/0011-process-privilege-model.md) | Unprivileged process user + scoped sudo | Accepted | Least-privilege: `CAP_NET_ADMIN` scoped to nft/wg only; `--cap-add NET_ADMIN` + `--network host` in Docker explicitly rejected (see T-011) |
| [ADR-0012](../adr/0012-docker-socket-proxy.md) | Unix socket proxy for production Docker (v1 line, 0.11.0) | **Accepted** | Demo Docker uses mock adapters only. 0.11.0 introduces `islandr-proxy` on the host — a small allowlisted daemon that executes exactly five wg/nft operations on behalf of the container; container runs with zero capabilities |
| [ADR-0013](../adr/0013-default-everyone-role.md) | Default "Everyone" role with auto-membership (0.11.0) | Accepted | Grant a shared resource once to all users (present and future) instead of per user/group; the role is seeded, protected server-side, and auto-membership is fixed to exactly one role |
| [ADR-0014](../adr/0014-device-discovery.md) | Device discovery by unprivileged CIDR scan (0.12.0) | Accepted | Admin-triggered scan of a site's own CIDR via unprivileged sockets (TCP `connect()` + connected-UDP ICMP-unreachable), producing a fingerprinted, bulk-importable host list; rejects raw-scan (CAP_NET_RAW) and a scan-agent as too privileged / too heavy |
| [ADR-0015](../adr/0015-builtin-tls-termination.md) | Built-in TLS termination (no mandatory reverse proxy) | Accepted | Quarkus TLS registry reload, not the older build-time-fixed `quarkus.http.ssl.*`; DB-managed cert via an in-memory `KeyStoreProvider` (private key never touches disk) or a file-path reference relying on Quarkus's own poll-reload; ACME deferred to a follow-up |
| [ADR-0019](../adr/0019-acme-hand-rolled-client.md) | ACME (Let's Encrypt) auto-provisioning via a hand-rolled client, not a library | Accepted | Removes the native-image risk outright instead of shrinking it: JWS via plain JDK `Signature`, JSON via the Jackson dependency already present, CSR via the same hand-rolled DER technique `TlsService` uses for PKCS1 import — no `acme4j`/Bouncy Castle added; v1 ships Let's Encrypt only, EAB/ZeroSSL deferred |
| [ADR-0026](../adr/0026-external-api-facade.md) | External API facade: API keys, separate resource surface, hand-written OpenAPI spec | Accepted | `/api/external/v1` as its own resource classes + API-key (Bearer) auth, parallel to the session-cookie-authenticated internal `/api/v1`; `docs/api/openapi.yml` hand-written/offline-generated — no `quarkus-smallrye-openapi` runtime dependency added, same hand-rolled-over-library reasoning as ADR-0019/0023 |

## Cross-cutting ADR consequences

- ADR-0001 creates **R-001** (native-image reflection bugs), **R-002** (build memory ≥ 6 GB), and **R-003** (ProcessBuilder blocking I/O thread).
- ADR-0003 creates **R-020** (semantically wrong ruleset passes `-c`), **R-021** (ufw masking undone by operator), **R-022** (two firewall surfaces on hub VM), and **R-023** (distros without nftables unsupported).
- ADR-0004 creates **R-030** (portable-SQL constraint erodes), **R-031** (SQLite team deployment without backup), **R-032** (Flyway dialect differences), **R-033** (SQLite activity-samples write rate), and **R-034** (SQLite Quarkus extension friction).
- ADR-0005 creates **R-040** (UCG-side rules drift from Islandr ACL in v1), **R-041** (intra-site lateral movement), **R-042** (v2 pull agent new component to maintain), and **R-043** (compromised hub publishes malicious ACL to v2 agent).
- ADR-0006 creates **R-050** (ruleset size grows with peers × ports), **R-051** (L4/L7 boundary misunderstood), **R-052** (resource maintenance falls on admin), **R-053** (wide ACL matrix UI for large sites), and **R-054** (all-ports grant silently widens).
- ADR-0007 creates **R-060** (DB exfiltration in plaintext mode), **R-061** (mode change leaves key material in DB), **R-062** (backup files contain peer private keys), **R-063** (re-display endpoint widens attack surface), and **R-064** (audit log growth from PEER_CONF_RESHOW).
- ADR-0009 creates **R-035** (EUPL-incompatible dependency added by future contributor).
- ADR-0011 creates **R-110** (ruleset file path writable by others → nftables injection), **R-111** (`wg set wg0 *` wildcard exploitable as `islandr` user), **R-112** (Docker v1 mock-only limitation; production requires systemd), and **R-113** (sudoers path broken by distro update).
- ADR-0012 creates **R-120** (proxy socket world-readable → unauthorized proxy commands) and **R-121** (proxy protocol unauthenticated within `islandr` user boundary).
- ADR-0013 creates **R-130** (a grant on the auto-membership `Everyone` role silently reaches all present and future users) and **R-131** (deleting/renaming/clearing the seeded role would break the "reaches all users" contract).
- ADR-0014 creates **R-140** (best-effort discovery misses fully-filtered hosts), **R-141** (scan is an authenticated recon primitive — ties T-013), and **R-142** (scan could become a connect-flood — ties T-014). It also materially addresses **R-052**.
- ADR-0015 creates **R-150** (decrypted TLS private key transient in heap), **R-151** (referenced-mode cert file permissions outside islandr's control), **R-152** (malformed/mismatched cert accepted before validation), and **R-153** (no renewal reminder — a managed certificate can silently expire).
- ADR-0019 creates **R-164** (HTTP-01 challenge endpoint is a new unauthenticated internet-facing surface — ties T-016), **R-165** (hand-rolled JWS/CSR code has no upstream security-patch stream), and **R-166** (a missed scheduled renewal could still let an ACME-managed certificate expire). It also closes **R-153** for the ACME mode specifically.
- ADR-0026 creates **R-184** (a leaked v1 API key is full-admin-equivalent, no per-key scoping yet) and **R-185** (hand-written OpenAPI spec can drift from the facade implementation) — ties **T-019** (static bearer API keys as a spoofable/leakable credential).

All risks are tracked in [Chapter 11](11-risks-and-technical-debt.md) with probability, impact, and mitigation references.
