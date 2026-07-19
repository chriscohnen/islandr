# Architecture Decision Records

Architectural decisions for Islandr, in the [Nygard ADR](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) format. Where useful, each ADR includes a 3-point Pugh Matrix (`-1` / `0` / `+1`, with `?` for cells that need team judgment) scoring alternatives against the chosen baseline.

When an ADR's status is "Accepted (inferred)", the rationale was reconstructed after the fact and should be confirmed before being treated as load-bearing.

## Index

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-quarkus-backend.md) | Quarkus as the backend framework | Accepted | 2026-05-30 |
| [0002](0002-vue-without-npm.md) | Vue 3 frontend without the npm toolchain | Accepted (with caveats) | 2026-05-30 |
| [0003](0003-nftables-replaces-ufw.md) | nftables replaces ufw on the hub VM | Accepted | 2026-05-30 |
| [0004](0004-sqlite-dev-postgres-prod.md) | SQLite for dev, PostgreSQL for prod | Accepted | 2026-05-30 |
| [0005](0005-hub-only-firewall.md) | Firewall enforcement stays on the hub VM; no UCG API access from the hub | Accepted | 2026-05-30 |
| [0006](0006-resource-level-acl.md) | Resource-level ACL with NIST RBAC0 | Accepted | 2026-05-30 |
| [0007](0007-private-key-retention.md) | Private-key retention policy (instance-wide, two modes in v1) | Accepted | 2026-05-30 |
| [0008](0008-runtime-settings-in-db.md) | Runtime settings live in the database, not in application.properties | Accepted | 2026-05-30 |
| [0009](0009-license-eupl-1.2.md) | License: EUPL-1.2 | Accepted | 2026-06-01 |
| [0010](0010-font-and-icon-asset-self-hosting.md) | Font and icon asset self-hosting | Accepted | 2026-06-04 |
| [0011](0011-process-privilege-model.md) | Process privilege model: unprivileged user + scoped sudo for nft and wg | Accepted | 2026-06-06 |
| [0012](0012-docker-socket-proxy.md) | Docker deployment via Unix socket proxy (v1 line, 0.11.0) | Accepted | 2026-06-28 |
| [0013](0013-default-everyone-role.md) | Default "Everyone" role with auto-membership (0.11.0) | Accepted | 2026-07-10 |
| [0014](0014-device-discovery.md) | Device discovery by unprivileged TCP-connect scan of a site's own CIDR (0.12.0) | Accepted | 2026-07-10 |
| [0015](0015-builtin-tls-termination.md) | Built-in TLS termination (no mandatory reverse proxy) | Accepted | 2026-07-19 |

## Status legend

- **Proposed** — under discussion, not yet decided
- **Accepted** — the decision is in effect
- **Accepted (inferred)** — rationale was reconstructed after the fact; confirm before relying on it
- **Accepted (with caveats)** — accepted, but a named open question must be resolved before locking in
- **Superseded by ADR-NNNN** — replaced; keep the file for traceability, do not delete

## Adding a new ADR

1. Copy the structure of an existing ADR (Context / Decision / Alternatives / Consequences / References).
2. Number it next in sequence (`0006-…`).
3. Score alternatives against the chosen baseline in a Pugh Matrix when the decision has more than one plausible path. Skip the matrix only when alternatives are not real.
4. Under Consequences, name the risks the decision creates with `R-NNN` IDs. These will be picked up in arc42 Chapter 11 when it's written.
5. Update this index.
