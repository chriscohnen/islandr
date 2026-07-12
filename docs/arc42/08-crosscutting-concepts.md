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
| T-013 | Discovery scan abused as a **recon primitive** — an attacker holding a stolen admin session (T-002) or an RCE (T-009) enumerates a remote site LAN through the hub's WireGuard route | Information Disclosure | Site network topology | The scan is admin-only (T-005) and can only target a site's **own declared CIDR** — there is no free-text range input, so it cannot be pointed at a network the operator has not already registered. TCP `connect()` is a full handshake the target logs (non-stealth by design), and every scan start is written to the append-only audit log (§8.4). Bounds the blast radius rather than removing it: an attacker who already owns an admin session can enumerate only networks the hub is already routed into ([ADR-0014](../adr/0014-device-discovery.md); risk R-141). |
| T-014 | Discovery scan abused as a **connect-flood** against a remote site network (large CIDR, or a scan triggered in a loop) | Denial of Service | Remote site network availability | Host-count cap of 1024 (`/22`); larger CIDRs are rejected with HTTP 409 before any probe is sent. Bounded probe concurrency, a short per-host timeout, and at most one active scan per site cap the outbound rate by construction; the scan is admin-triggered on an explicit click, never a background poll ([ADR-0014](../adr/0014-device-discovery.md); risk R-142). |

## 8.2 Security

Mitigations for the STRIDE threats above:

- **Principle of least privilege** — the `islandr` process user has no login shell, no home directory, and only `sudo` access to specific `nft` and `wg` commands ([ADR-0011](../adr/0011-process-privilege-model.md)).
- **Input sanitization** — all free-text strings that enter nftables rules (resource names, labels) are sanitized. Structural values (IPs, ports, transport) are typed from the DB — there is no string-interpolation path for them.
- **Authentication on every request** — `SessionFilter` rejects unauthenticated requests before they reach any resource. Admin-only endpoints check `session.isAdmin()`.
- **No credential storage on hub** — no UCG credentials, no private keys (default mode), no external service secrets beyond OIDC client credentials.
- **Dependency CVE tracking** — GitHub Dependabot + CodeQL scan on every push. PostgreSQL driver pinned above the CVE-fixed version (42.7.11, SCRAM auth CPU exhaustion).
- **No privileged Docker in production** — `--cap-add NET_ADMIN` + `--network host` is an explicitly rejected pattern (T-011). Demo Docker is mock-only. 0.11.0 (v1 line) introduces a Unix socket proxy ([ADR-0012](../adr/0012-docker-socket-proxy.md)) that gives the container a narrow, auditable, allowlisted channel to the host kernel — without any Linux capabilities.
- **Discovery scans are unprivileged, consent-bounded and rate-bounded** (T-013, T-014) — host liveness is established with user-space sockets only (TCP `connect()` plus a connected-`DatagramSocket` ICMP-unreachable probe), so discovery adds **no** raw socket, no `CAP_NET_RAW`, and no new `sudoers` entry to the ADR-0011 privilege model. A scan is admin-only, runs only against a site's own declared CIDR (≤ 1024 hosts), is capped in concurrency and per-host timeout, allows one active job per site, and is audited on start and on import ([ADR-0014](../adr/0014-device-discovery.md)).

## 8.3 Testing

| Layer | What | Tool | Traceability |
|---|---|---|---|
| Unit | Domain logic in isolation (rule building, ACL resolution, IP validation) | JUnit 5 + AssertJ | Use Case IDs in `@DisplayName` |
| Integration | REST endpoints with real (in-memory SQLite) database | Quarkus `@QuarkusTest` + REST Assured | Use Case IDs in `@DisplayName` |
| Firewall adapter | `RealNftablesAdapter` via `DryRunNftablesAdapter` (validates rules, does not apply) | JUnit 5 | F-08 |
| WireGuard adapter | `RealWgAdapter` tested via `DryRunWgAdapter` | JUnit 5 | F-02–F-05 |
| Encrypted key retention | Encryption/decryption in the `crypto` package; `PeerResourceEncryptedRetentionTest` verifies the full `encrypted` mode flow (store, retrieve, decrypt, re-display) | JUnit 5 | [ADR-0007](../adr/0007-private-key-retention.md) (`encrypted` mode) |
| Discovery | `HostProbe` (TCP open/refused/timeout, UDP port-unreachable) against a loopback socket; `CidrHosts` enumeration and the ≤1024-host cap; `TypeFingerprint` port→type table; `DiscoveryScanner` with an injected fake probe (no network); `DiscoveryResource` end-to-end in mock mode | JUnit 5 + AssertJ, Quarkus `@QuarkusTest` | UC-05, F-21, BR-032…BR-037, [ADR-0014](../adr/0014-device-discovery.md) |
| Coverage | Collected by JaCoCo, reported to Codecov. No line-coverage threshold is enforced in CI yet — setting a threshold is tracked in the backlog. | `./gradlew test jacocoTestReport` | — |

The `%test` profile uses `jdbc:sqlite:file::memory:?cache=shared`, Flyway `clean-at-start`, and mock adapters. Tests do not require a Linux host or kernel capabilities.

## 8.4 Observability

| Signal | What is captured | Access |
|---|---|---|
| Structured logs | Quarkus JSON logging; request/response for errors; adapter call results (wg, nft) | `journalctl -u islandr` or Docker logs |
| Audit log | Every mutating API action: actor, action, target entity, timestamp | `GET /api/v1/audit`, Admin Console audit view |
| Dashboard endpoint | Online peer count, firewall last-reload timestamp and status, nftables rule count | `GET /api/v1/status`, Admin Console dashboard |
| Activity samples | Per-peer: last handshake, rx/tx bytes (30s polling, 30d retention) | `GET /api/v1/peers/{id}/activity` |
| Discovery audit events | `discovery.scan_started` (actor, site, CIDR, host count) and `discovery.import` (actor, site, created IPs) — a scan reaches into a remote network, so who scanned what, and when, is on the record (T-013) | `GET /api/v1/audit`, Admin Console audit view |

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
| Discovery scan finds no hosts | **A result, not an error.** The job completes with `state=done` and an empty host list; the UI says so plainly and offers manual add. Discovery is best-effort: a fully-filtered, ICMP-dropping host is invisible to an unprivileged probe (R-140) | Operator adds the resource by hand; no retry loop, no error state |
| Discovery preconditions unmet (real scan whose declared tunnel gateway is stale, CIDR > /22) | Rejected **before any probe is sent** with HTTP 409 and copy naming the specific cause. A site with no gateway peer is hub-local and allowed; mock mode skips the route check; a scan still running for the site is superseded, not rejected | Operator reconnects the site gateway or narrows the CIDR (T-014) |
| Hub restarts mid-scan | The in-memory job is lost (TD-005); no partial results are persisted and no resources are created | Operator re-runs the scan — it is cheap to repeat and `import` is idempotent on `(site, ip)` |

There is no circuit breaker in v1 because the only external calls are shell invocations (synchronous, fast-fail) and OIDC JWKS fetches (cached, optional). A circuit breaker would add complexity without measurable benefit at this scale.
