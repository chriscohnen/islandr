# 4. Solution Strategy

## 4.1 Key Decisions

| Goal | Strategy | ADR |
|---|---|---|
| Single-binary deployment (Q-3) | Quarkus 3 with GraalVM Mandrel native image. JVM for dev, native binary for production. | [ADR-0001](../adr/0001-quarkus-backend.md) |
| Frontend without npm (TC-6) | Vue.js via ESM import maps in dev. esbuild-bundled static assets served by the Quarkus binary in production. | [ADR-0002](../adr/0002-vue-without-npm.md) |
| Firewall correctness (Q-2) | nftables replaces ufw. Full ruleset recomputed from DB state on every relevant change. Validated before every atomic reload. | [ADR-0003](../adr/0003-nftables-replaces-ufw.md) |
| Flexible persistence (TC-5) | SQLite for dev and small self-hosters. PostgreSQL for team deployments. Same code path — only the JDBC driver and dialect differ. | [ADR-0004](../adr/0004-sqlite-dev-postgres-prod.md) |
| Hub security (Q-1) | No UCG credentials on the hub. nftables enforces only hub-local rules. v2 pull-mode agent for internal network enforcement. | [ADR-0005](../adr/0005-hub-only-firewall.md) |
| Access modelling (Q-4, Q-5) | NIST RBAC0 with resource-level granularity. `(peer.ip, resource.ip, transport, port)` per rule. No site-CIDR targets. | [ADR-0006](../adr/0006-resource-level-acl.md) |
| Private key safety | `retention=never` by default: private key in create-peer response only, never stored. `plaintext` opt-in for re-display. | [ADR-0007](../adr/0007-private-key-retention.md) |
| Runtime settings | All tuneable config (WG subnet, retention mode, OIDC providers) lives in the `settings` DB table. No restart required after change. | [ADR-0008](../adr/0008-runtime-settings-in-db.md) |
| Process privilege (TC-9) | Unprivileged `islandr` system user. `nft` and `wg` access via scoped `sudoers` rules or `CAP_NET_ADMIN`. | [ADR-0011](../adr/0011-process-privilege-model.md) |
| Auditability (Q-5) | Dedicated `audit` package with append-only log. Every mutating API action invokes `AuditService.log()` via CDI injection. No `DELETE` or `UPDATE` endpoint exists for audit entries — enforced at the application level and verifiable by code search. | — |

## 4.2 Architecture Style

Islandr is a **monolith** — a single Quarkus process that hosts the REST API, serves the static frontend files, runs the activity poller, and manages all domain logic. There is no microservice boundary in v1.

The internal structure is **package-by-domain**: each package (`peer`, `acl`, `firewall`, `auth`, `identity`, `user`, `audit`, `settings`, `dashboard`, `wg`) owns its entities, services, and REST resources. Packages communicate through direct CDI injection, not events or queues.

This is intentional for a single-hub, single-team tool. The operational simplicity of one process outweighs the flexibility of a distributed architecture at this scale.

## 4.3 Firewall Rule Generation

The ruleset is always a pure function of current DB state. On every triggering event, the backend:

1. Loads all enabled peers with their users.
2. Resolves `user.roles → role.grants → (resource.ip, resourcePort.port, resourcePort.transport)` for each peer.
3. Builds one nftables `accept` rule per `(peer.assignedIP, resource.ip, transport, port)` tuple.
4. Validates the ruleset string with `nft -c -f <tempfile>`.
5. If valid, atomically applies with `nft -f <tempfile>`.
6. If invalid, keeps existing rules and surfaces the nft error in the UI.

There is no partial-update path. The full ruleset is always regenerated and revalidated.

## 4.4 Activity Polling

WireGuard reports only the most recent handshake per peer via `wg show`. Islandr polls every 30 seconds (configurable) and persists two kinds of data:

- **Aggregated state on `Peer`**: `lastSeenAt`, `totalRxBytes`, `totalTxBytes` — survives `wg` restarts, fast to read.
- **`PeerActivitySample`**: time-series samples for charts, bounded to a configurable retention window (default 30 days).

Live "is this peer online?" is answered from `wg show` directly (last handshake ≤ 3 minutes = active).
