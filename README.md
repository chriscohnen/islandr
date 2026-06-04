# Islandr

> Self-hosted WireGuard access management. A web dashboard for peers, users, and group-based ACLs — for hub-spoke topologies with mixed clients (road warriors, site gateways).

**Status:** Pre-alpha, walking skeleton runs. Backend with 171 green tests across 8 domains (users, peers, settings, auth, identity, RBAC, nftables/firewall, ACL). Frontend covers login, sidebar shell, user/peer admin, settings, identity, self-service portal, ACL matrix, audit log, and network/resource management.

---

## Why "Islandr"?

A remote employee on the home office is an **islander** — sitting on their own isolated IT island, looking for a safe way back to the mainland. Islandr is the ferry: every device, every site, every home office connected to the corporate "mainland" without leaving anyone stranded on a silo. The dropped `e` is the modern-tech-startup spelling. Full story in [islandr-name.md](islandr-name.md).

The internal working title is `wg-access`. The UI brand "Bastion" used in the design handoff is a placeholder — Islandr is the product name.

## What it does

Islandr unifies four things that today require CLI work, manual tutorials, and emailing config files around:

1. **Peers** — who / which device may connect
2. **Groups & ACLs** — who may reach what
3. **Firewall** — what enforces those rules technically (nftables)
4. **Self-service** — how end users get their configs without an admin in the loop

A hub VM with a public IP runs WireGuard, nftables, and the Islandr backend. Site gateways (UniFi UCG and friends) are static peers configured on their own side. Road warrior clients are the primary target for dynamic management.

```
[Road Warrior]──┐
[Home Office]───┼──wg peer──► [Hub VM / Islandr] ◄──wg peer──[UCG site]
[Mobile]────────┘
```

## Two surfaces, one brand

| | **Admin Console** | **Self-Service Portal** |
|---|---|---|
| Who | Sysadmins, IT leads | Employees, family, contractors |
| Character | Dense, data-rich | Airy, guided, banking-onboarding tone |
| Vocabulary | Peer, ACL, CIDR, Handshake | Device, access, connection |
| Layout | Sidebar + topbar + multi-column | Centered single column ≤720px |

Both share the same design tokens. Language is German, informal `Du`. The full design system lives in [design_handoff_bastion_design_system/](design_handoff_bastion_design_system/) — tokens, component primitives, screen-by-screen specs, and clickable HTML prototypes.

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Quarkus 3.29.4 (Java 21), Hibernate ORM Panache, Quarkus REST, Flyway |
| Database | SQLite (dev/test, in-memory for tests) / PostgreSQL (prod) |
| Frontend | Vue 3 + vue-router (importmap from `/vendor/`, **no npm/build step**) |
| Auth | ENV-bootstrapped local admin + OIDC (Microsoft 365 / Google), custom JDK-HttpClient flow with JWKS + RS256 verification, no `quarkus-oidc` |
| Avatar pipeline | MS Graph `/me/photo` → Google `picture` claim → optional Gravatar (cached in DB) |
| WireGuard mgmt | `wg` / `wg-quick` CLI via Java `ProcessBuilder` (real adapter) + in-memory mock adapter for dev/CI |
| QR codes | zxing-core / zxing-javase (PNG in-memory, no AWT display) |
| Firewall | nftables via `nft` CLI — RuleBuilder + atomic reload + mock adapter for dev/CI |
| Deployment | systemd + Quarkus native binary (GraalVM), optional Docker Compose |
| TLS | Caddy or Let's Encrypt at the edge |

Quarkus was chosen for fast iteration (live coding, dev services, native build). Rust was considered and dropped — the iteration cycle in Quarkus is faster for a team that already knows the JVM. See [docs/adr/0001-quarkus-backend.md](docs/adr/0001-quarkus-backend.md).

A deliberate stack choice: **no npm-heavy frontend toolchain**. Vue runs from CDN ESM in dev and is self-hosted under `/vendor/` for production. See [docs/adr/0002-vue-without-npm.md](docs/adr/0002-vue-without-npm.md).

Identity is intentionally implemented without `quarkus-oidc` so that all provider configuration (client id, secret, tenant, allowed email domains, enabled flag) lives in the DB and is editable via the Admin Console at runtime — no `application.properties` round-trip, no restart. Mutual exclusion is enforced at the service layer: at most one OIDC provider may be active at any time. The local ENV-admin is always available as a recovery path (`ISLANDR_ADMIN_USER` / `ISLANDR_ADMIN_PASSWORD`).

## Running it locally

Dev server (Quarkus live coding):

```bash
./gradlew quarkusDev
# → http://localhost:8080
```

The `%dev` profile ships with `islandr.admin.user=admin` / `islandr.admin.password=admin` so the local login just works. **In prod the password has no default** — operators must set `ISLANDR_ADMIN_PASSWORD` as an env var, otherwise `/api/v1/auth/login` returns HTTP 503 ("local admin login disabled"). This is deliberate: a known default in containers is a security hole; a loud failure is not.

Tests (171, runs in ~9 s after warm start):

```bash
./gradlew test
```

The test profile uses an in-memory SQLite that's wiped per run (`clean-at-start=true`) and a `MockWgAdapter` so no `wg`/`wg-quick` binary is needed.

## Repository layout

```
islandr/
├── README.md                                # this file
├── CLAUDE.md                                # guidance for Claude Code
├── build.gradle.kts                         # Gradle 9.1 / Kotlin DSL
├── wg-dashboard-plan.md                     # original product plan / RFC
├── islandr-name.md                          # name story
├── docs/
│   ├── prd.md                               # Product Requirements
│   └── adr/                                 # Architecture Decision Records
│       ├── README.md
│       ├── 0001-quarkus-backend.md
│       ├── 0002-vue-without-npm.md
│       ├── 0003-nftables-replaces-ufw.md
│       ├── 0004-sqlite-dev-postgres-prod.md
│       ├── 0005-hub-only-firewall.md
│       ├── 0006-resource-level-acl.md
│       ├── 0007-private-key-retention.md
│       └── 0008-runtime-settings-in-db.md
├── src/
│   ├── main/java/de/chriscohnen/islandr/
│   │   ├── auth/        # Session, SessionFilter, AdminBootstrap, AuthResource, OidcAuthResource
│   │   ├── identity/    # OidcProvider, JwksCache, IdTokenVerifier, OidcLoginService, AvatarFetcher
│   │   ├── peer/        # Peer entity + DTO + Resource + Service + IpSubnet
│   │   ├── settings/    # Singleton settings (WG topology, retention mode, Gravatar toggle)
│   │   ├── user/        # User + Resource + AvatarService (3-tier: cached → Gravatar → 404)
│   │   └── wg/          # WgAdapter (real shells out, mock for dev/CI)
│   ├── main/resources/
│   │   ├── application.properties
│   │   ├── db/migration/V1..V5__*.sql       # Flyway, portable SQL only
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 171 tests, JUnit 5 + RestAssured + AssertJ
└── design_handoff_bastion_design_system/    # design system + prototypes
```

arc42 architecture chapters will be added once the ADRs are confirmed.

## Status & roadmap

**Done (walking skeleton)**
- User CRUD + email-unique constraint
- Peer CRUD: Client + Site peer types, IP suggestion based on WG subnet, CIDR-overlap validation
- Server-side keypair generation OR admin-imported keys (public-only / public+private, validated via `wg pubkey`)
- QR code + `.conf` download (one-time-secret in `never` mode; re-show available in `plaintext` mode)
- Singleton settings with retention mode + Gravatar toggle
- Local ENV admin (SHA-256 constant-time, 12h server-side revocable sessions)
- OIDC: Microsoft 365 + Google, full code-exchange + JWKS-cached RS256 verify, all config GUI-editable
- Mutual exclusion: at most one OIDC provider active; admin can swap via confirm dialog
- MS Graph photo + Google `picture` claim cached as user avatar; Gravatar fallback for local users (opt-in, off by default)
- Sidebar shell, dark/light token system, dedicated Identity / Users / Peers / Settings views

**Done (RBAC + Self-Service)**
- `users.is_admin` flag + admin assignment UI (V6 migration, `PUT /users/{id}/admin`)
- `Auth.requireAdmin` guard on every admin endpoint (users / peers / settings / identity providers)
- Public `GET /auth/providers` returning only `providerKey` + `enabled` for the unauthenticated login page
- Role-aware sidebar: non-admins see only "Mein Zugang"
- Self-service peer endpoints (`GET/POST /peers/mine`, `PUT /peers/mine/{id}/public-key`, `GET /peers/mine/{id}/conf`) scoped to `session.userId`
- MyAccessView: own device list, "add device" with server-generated keypair or imported public key, key rotation, QR/.conf reshow when retention=plaintext
- Frontend auth source-of-truth moved to `/auth/me` (no more localStorage drift after OIDC callback)
- OIDC `allowedDomains` is now optional — empty allowlist lets through whatever the IdP consent screen allows (covers Gmail-family deployments)

**Done (nftables + ACL + Audit)**
- nftables ruleset generation (atomic reload via RuleBuilder, cold-start-safe `add table` before `flush table`)
- Sites + Resources + Ports, Resource-level ACL (Roles → Resource grants, limited-port mode)
- Activity poller (last seen, last endpoint; bytes-counter delta logic still TODO)
- Audit log with cursor-based pagination, actor/action filters, meta-JSON expand

**v1 — Core (still to do)**
- Bytes-counter delta in Activity poller (`lastSampledRxBytes`/`TxBytes` columns)
- Audit log: Saga / try-compensate rollback between `wg.setPeer` and `nftables-apply`

**v2 — Hardening**
- Entra-ID role-claim mapping
- Internal pull-mode agent for UCG provisioning
- Peer expiry / auto-disable
- Email notifications
- Network device discovery: optional "Discover" button on `printer`/`nas`/`router` resource cards — one-shot SNMP (UDP 161, community `public`) or IPP (TCP 631) request from the hub fills in name, description and supported capabilities automatically. Requires snmp4j or shelling `snmpget`; mDNS/Bonjour (`avahi-browse`) as fallback for printers that don't speak SNMP.

**v3 — Scale**
- Multi-hub support
- API key management
- Prometheus metrics

Full plan: [wg-dashboard-plan.md](wg-dashboard-plan.md).

## Documentation

- [docs/prd.md](docs/prd.md) — Product Requirements Document
- [docs/architecture.md](docs/architecture.md) — C4 Level 1 (System Context) and Level 2 (Container) views
- [docs/adr/](docs/adr/) — Architecture Decision Records (Nygard format, Pugh-Matrix where useful)
- [wg-dashboard-plan.md](wg-dashboard-plan.md) — original RFC and product plan
- [design_handoff_bastion_design_system/README.md](design_handoff_bastion_design_system/README.md) — design system handoff (tokens, screens, voice)

## License

**EUPL-1.2** (EU-governed, copyleft, AGPL-compatible). The `LICENSE` file and ADR-0009 are still to be added.
