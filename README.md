# Islandr

> Self-hosted WireGuard access management. A web dashboard for peers, users, and group-based ACLs — for hub-spoke topologies with mixed clients (road warriors, site gateways).

**Status:** Early access — core features complete, live production testing in progress. Backend with 171 green tests across 8 domains (users, peers, settings, auth, identity, RBAC, nftables/firewall, ACL). Frontend covers login, sidebar shell, user/peer admin, settings, identity, self-service portal, ACL matrix, audit log, and network/resource management.

---

## Why "Islandr"?

A remote employee on the home office is an **islander** — sitting on their own isolated IT island, looking for a safe way back to the mainland. Islandr is the ferry: every device, every site, every home office connected to the corporate "mainland" without leaving anyone stranded on a silo. The dropped `e` is the modern-tech-startup spelling. Full story in [islandr-name.md](islandr-name.md).

The internal working title is `wg-access`. Islandr is the product name.

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

Both share the same design tokens. UI is bilingual DE/EN, switchable at runtime. German default, informal `Du`.

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
├── docs/
│   └── adr/                                 # Architecture Decision Records
│       ├── README.md
│       ├── 0001-quarkus-backend.md
│       ├── 0002-vue-without-npm.md
│       ├── 0003-nftables-replaces-ufw.md
│       ├── 0004-sqlite-dev-postgres-prod.md
│       ├── 0005-hub-only-firewall.md
│       ├── 0006-resource-level-acl.md
│       ├── 0007-private-key-retention.md
│       ├── 0008-runtime-settings-in-db.md
│       ├── 0009-license-eupl-1.2.md
│       └── 0010-font-and-icon-asset-self-hosting.md
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
│   │   ├── db/migration/                    # Flyway migrations V1–V13, portable SQL
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 171 tests, JUnit 5 + RestAssured + AssertJ
```


## Status & roadmap

**Early access — core feature set complete, live production testing in progress.**

### What works today

**Authentication & identity**
- Local admin login (ENV-bootstrapped, SHA-256 constant-time, 12h revocable sessions)
- OIDC: Microsoft 365 / Entra ID and Google — full code-exchange + JWKS-cached RS256 verification, all config GUI-editable at runtime without restart
- At most one OIDC provider active at a time; admin can swap via confirm dialog
- Avatar pipeline: MS Graph photo → Google picture claim → Gravatar (opt-in) → deterministic initials fallback

**User & peer management**
- User CRUD with admin/end-user role assignment
- Peer CRUD: client and site peer types, IP suggestion from WG subnet, CIDR-overlap validation
- Server-side keypair generation or admin-imported public key (validated via `wg pubkey`)
- QR code + `.conf` download with one-time-secret pattern; re-show in `plaintext` retention mode

**Networks, resources & firewall**
- Sites and resources with typed resource cards (computer, router, printer, NAS, camera, IoT, virt-host, management)
- Port groups and resource-level ACL: roles → resource grants, with per-port or all-ports mode
- nftables ruleset generation — atomic reload via RuleBuilder, cold-start-safe, mock adapter for dev/CI
- Activity poller: last seen, last endpoint, bytes-counter delta (rx/tx)

**Self-service portal**
- End users add their own devices via a 3-step flow: platform → QR + `.conf` → wait for first handshake
- Key rotation, device list, accessible resource overview with protocol icons
- RDP quicklaunch: resources with port 3389/RDP render a download button that generates a ready-to-open `.rdp` file

**Observability**
- Audit log with cursor-based pagination, actor/action/target filters, meta-JSON expand

**Bilingual UI**
- German (default) and English, switchable at runtime without reload

**v2 — Hardening**
- Entra-ID role-claim mapping
- Internal pull-mode agent for UCG provisioning
- Peer expiry / auto-disable
- Email notifications

**v3 — Scale**
- Multi-hub support
- API key management
- Prometheus metrics

## Documentation

- [docs/prd.md](docs/prd.md) — Product Requirements Document
- [docs/adr/](docs/adr/) — Architecture Decision Records (Nygard format, Pugh matrix)

## License

**EUPL-1.2** (EU-governed, copyleft, AGPL-compatible). See [docs/adr/0009-license-eupl-1.2.md](docs/adr/0009-license-eupl-1.2.md) for the rationale.
