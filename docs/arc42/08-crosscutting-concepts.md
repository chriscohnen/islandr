# 8. Crosscutting Concepts

## 8.1 Threat Model (STRIDE)

The hub VM is internet-exposed. It listens on UDP 51820 (WireGuard) and TCP 443 (HTTPS). Every component reachable from the internet is a potential attack surface.

| ID | Threat | STRIDE Category | Asset | Mitigation |
|---|---|---|---|---|
| T-001 | Compromised hub VM used to reconfigure internal UCG firewall | Elevation of Privilege | Internal network | Hub holds no UCG credentials; no outbound connections into the trusted network ([ADR-0005](../adr/0005-hub-only-firewall.md)) |
| T-002 | Admin session token stolen via XSS | Spoofing | Admin session | Session tokens are `HttpOnly` cookies; CSP headers enforced; no inline scripts |
| T-003 | nftables ruleset poisoned via unsanitized input | Tampering | Firewall rules | All structural parameters (IPs, ports) are typed and DB-constrained; comment-field strings are sanitized before inclusion in ruleset (PRD N-12) |
| T-004 | Private key exposed at rest | Information Disclosure | Peer private key | Default `retention=never`: key exists only in the create-peer API response, never persisted ([ADR-0007](../adr/0007-private-key-retention.md)) |
| T-005 | Unauthenticated API access | Spoofing | All API endpoints | `SessionFilter` enforces authentication on every `/api/v1/` request; admin endpoints additionally require `isAdmin=true` |
| T-006 | Audit log tampered or deleted | Repudiation | Audit log | No `DELETE` or `UPDATE` endpoint exists for audit entries; audit table has no application-level delete path |
| T-007 | SQLite file read by local attacker | Information Disclosure | Database | File-system permissions; OS-level disk encryption recommended. In `encrypted` retention mode, private keys are AES-256-GCM encrypted at rest — a DB-only exfiltration cannot recover peer keys without the separate master key ([ADR-0007](../adr/0007-private-key-retention.md)) |
| T-008 | nft CLI injection via resource name | Tampering | Firewall rules | Resource names go only into nftables `comment` fields; sanitized (quotes, backslashes, newlines stripped) |
| T-009 | RCE via Quarkus HTTP layer / Jackson deserializer | Elevation of Privilege | Host OS | Unprivileged process user; scoped sudo; no capabilities beyond NET_ADMIN ([ADR-0011](../adr/0011-process-privilege-model.md)) |
| T-010 | OIDC token replay or forgery | Spoofing | User sessions | Tokens verified locally with JWKS (signature, expiry, audience, issuer); nonce checked for Authorization Code Flow |
| T-011 | Docker `--cap-add NET_ADMIN` + `--network host` used in production container | Elevation of Privilege | Host network stack | With `--network host`, `CAP_NET_ADMIN` inside the container covers all network namespaces on the host — a compromised process gains root-equivalent control over every nftables table and network interface. **Rejected as an architectural constraint**: v1 Docker image uses mock adapters only; production requires systemd ([ADR-0011](../adr/0011-process-privilege-model.md)). 0.11.0 (v1 line) uses a Unix socket proxy ([ADR-0012](../adr/0012-docker-socket-proxy.md)) so the container runs without any capabilities. |
| T-012 | Hub UDP 51820 flooded with spoofed WireGuard initiation packets, exhausting CPU for handshake processing | Denial of Service | WireGuard interface availability | WireGuard's built-in cookie mechanism rate-limits initiations under load (Retry Source cookies per RFC 8369 §5.4); fail2ban on TCP 443 covers HTTP-layer DoS; rate-limit rules in the host `inet filter input` table (outside the `islandr` table) handle volumetric UDP flood mitigation — documented in the install guide. No application-level mitigation is required beyond what the kernel enforces. |

## 8.2 Security

Mitigations for the STRIDE threats above:

- **Principle of least privilege** — the `islandr` process user has no login shell, no home directory, and only `sudo` access to specific `nft` and `wg` commands ([ADR-0011](../adr/0011-process-privilege-model.md)).
- **Input sanitization** — all free-text strings that enter nftables rules (resource names, labels) are sanitized. Structural values (IPs, ports, transport) are typed from the DB — there is no string-interpolation path for them.
- **Authentication on every request** — `SessionFilter` rejects unauthenticated requests before they reach any resource. Admin-only endpoints check `session.isAdmin()`.
- **No credential storage on hub** — no UCG credentials, no private keys (default mode), no external service secrets beyond OIDC client credentials.
- **Dependency CVE tracking** — GitHub Dependabot + CodeQL scan on every push. PostgreSQL driver pinned above the CVE-fixed version (42.7.11, SCRAM auth CPU exhaustion).
- **No privileged Docker in production** — `--cap-add NET_ADMIN` + `--network host` is an explicitly rejected pattern (T-011). Demo Docker is mock-only. 0.11.0 (v1 line) introduces a Unix socket proxy ([ADR-0012](../adr/0012-docker-socket-proxy.md)) that gives the container a narrow, auditable, allowlisted channel to the host kernel — without any Linux capabilities.

## 8.3 Testing

| Layer | What | Tool | Traceability |
|---|---|---|---|
| Unit | Domain logic in isolation (rule building, ACL resolution, IP validation) | JUnit 5 + AssertJ | Use Case IDs in `@DisplayName` |
| Integration | REST endpoints with real (in-memory SQLite) database | Quarkus `@QuarkusTest` + REST Assured | Use Case IDs in `@DisplayName` |
| Firewall adapter | `RealNftablesAdapter` via `DryRunNftablesAdapter` (validates rules, does not apply) | JUnit 5 | F-08 |
| WireGuard adapter | `RealWgAdapter` tested via `DryRunWgAdapter` | JUnit 5 | F-02–F-05 |
| Encrypted key retention | Encryption/decryption in the `crypto` package; `PeerResourceEncryptedRetentionTest` verifies the full `encrypted` mode flow (store, retrieve, decrypt, re-display) | JUnit 5 | [ADR-0007](../adr/0007-private-key-retention.md) (`encrypted` mode) |
| Coverage | Collected by JaCoCo, reported to Codecov. No line-coverage threshold is enforced in CI yet — setting a threshold is tracked in the backlog. | `./gradlew test jacocoTestReport` | — |

The `%test` profile uses `jdbc:sqlite:file::memory:?cache=shared`, Flyway `clean-at-start`, and mock adapters. Tests do not require a Linux host or kernel capabilities.

## 8.4 Observability

| Signal | What is captured | Access |
|---|---|---|
| Structured logs | Quarkus JSON logging; request/response for errors; adapter call results (wg, nft) | `journalctl -u islandr` or Docker logs |
| Audit log | Every mutating API action: actor, action, target entity, timestamp | `GET /api/v1/audit`, Admin Console audit view |
| Dashboard endpoint | Online peer count, firewall last-reload timestamp and status, nftables rule count | `GET /api/v1/status`, Admin Console dashboard |
| Activity samples | Per-peer: last handshake, rx/tx bytes (30s polling, 30d retention) | `GET /api/v1/peers/{id}/activity` |

There is no metrics endpoint (Prometheus/OpenTelemetry) in v1. The dashboard endpoint covers operational state. Adding Micrometer is a low-friction next step if monitoring integration is required.

## 8.5 Error Handling

| Failure | Behavior | Recovery |
|---|---|---|
| `nft -c -f` validation fails | Existing ruleset stays in place; 422 returned to caller with nft stderr; error shown in Admin Console | Admin fixes the configuration; change is re-submitted |
| `nft -f` apply fails after successful validation | Error logged; 500 returned; dashboard shows last-known reload status | Re-submit triggers a new full recompute + retry |
| `wg set` fails | Peer is not added to the WireGuard interface; 500 returned; no DB write for the peer | Admin retries; root cause is a kernel/capability issue — see install guide |
| WireGuard interface unavailable at startup | FirewallBootstrap logs error and continues; Islandr starts and serves UI | Admin fixes wg0 interface, triggers manual firewall re-apply via Admin Console |
| SQLite locked | Quarkus Agroal connection pool queues the request; SQLite WAL mode serializes writes | Transparent to the caller for typical single-user admin load |
| OIDC provider unavailable | Token verification fails; login with OIDC fails; local admin login still works | Local admin account is always available as fallback |
| JWKS cache stale | JwksCache retries fetch on verification failure before rejecting the token | Transparent for short outages (< TTL) |

There is no circuit breaker in v1 because the only external calls are shell invocations (synchronous, fast-fail) and OIDC JWKS fetches (cached, optional). A circuit breaker would add complexity without measurable benefit at this scale.
