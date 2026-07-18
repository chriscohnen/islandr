# Islandr

<p align="center">
  <img src="https://islandr-gateway.net/islandr-brand/icon-512.png" width="80" alt="Islandr logo" />
</p>

<p align="center">
  <a href="https://github.com/chriscohnen/islandr/actions/workflows/ci.yml"><img src="https://github.com/chriscohnen/islandr/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://codecov.io/gh/chriscohnen/islandr"><img src="https://codecov.io/gh/chriscohnen/islandr/graph/badge.svg" alt="Coverage"></a>
  <a href="https://github.com/chriscohnen/islandr/releases/latest"><img src="https://img.shields.io/github/v/release/chriscohnen/islandr?label=release" alt="Latest release"></a>
  <a href="https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12"><img src="https://img.shields.io/badge/licence-EUPL--1.2-blue" alt="Licence EUPL-1.2"></a>
  <img src="https://img.shields.io/badge/java-21-orange" alt="Java 21">
  <img src="https://img.shields.io/badge/built%20with-Quarkus-4695EB" alt="Built with Quarkus">
</p>

<p align="center"><b>Self-hosted WireGuard access management.</b><br>
Peers, users, group-based ACLs and a self-service portal — one native binary, no SaaS.</p>

---

> **[islandr-gateway.net](https://islandr-gateway.net)** — landing page with setup guide and architecture overview.

---

> [!NOTE]
> **Early access — perfect for a homelab or a spare VM, not your production gateway just yet.**
> Islandr drives WireGuard and nftables directly (`wg set`, `ip link`, `nft`), so point it at a test box or lab network first and back up `/etc/wireguard/` and the database before upgrading — pre-1.0 releases can still bring breaking changes.
> This is exactly the stage where testers make the biggest difference. Kick the tyres, and if you hit a rough edge [open an issue](https://github.com/chriscohnen/islandr/issues) — that feedback is what moves it toward 1.0. Starring or watching the repo is the easiest way to follow releases.

![Dashboard screenshot](https://islandr-gateway.net/screenshots/light/dashboard.png)

---

## Who is this for?

Small teams, homelabs, and remote-first setups that want **sovereign WireGuard access management** without a SaaS control plane:

- Your ISP gives you CG-NAT and no fixed IPv4
- Your router has no WireGuard server mode (older Fritz!Box, or you don't want it there)
- You need more than one site — home, office, lab — with central user and ACL management
- You want employees to self-service their own device configs, not email you for a `.conf` file
- Data sovereignty matters — no connection metadata leaving your network

Islandr is **not** Zero Trust Network Access. It is managed VPN access with network segmentation — simpler, cheaper, fully under your control. If you need ZTNA, look at Teleport. If two devices and manual key files are enough, `wg-easy` is simpler. If a SaaS control plane is fine, Tailscale is excellent.

---

## Why "Islandr"?

A remote employee on the home office is an **islander** — sitting on their own isolated IT island, looking for a safe way back to the mainland. Islandr is the ferry: every device, every site, every home office connected to the corporate "mainland" without leaving anyone stranded on a silo. The dropped `e` is the modern-tech-startup spelling.

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

![QR code & config download](https://islandr-gateway.net/screenshots/light/qr-conf.png)

![Self-service portal](https://islandr-gateway.net/screenshots/light/self-service.png)

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

## Quickstart

Pre-built binaries for Linux x86_64 and ARM64 are attached to every [GitHub Release](https://github.com/chriscohnen/islandr/releases/latest):

```bash
ARCH=$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')
curl -fsSL "https://github.com/chriscohnen/islandr/releases/latest/download/islandr-runner-linux-${ARCH}" -o /tmp/islandr
curl -fsSL "https://github.com/chriscohnen/islandr/releases/latest/download/islandr-runner-linux-${ARCH}.sha256" | sha256sum -c -
sudo install -m 0755 /tmp/islandr /usr/local/bin/islandr
```

Or run the container image (published to GHCR for `amd64` and `arm64`):

```bash
docker run -d -p 7080:8080 -e ISLANDR_ADMIN_PASSWORD=change-me \
  -v islandr-data:/var/lib/islandr ghcr.io/chriscohnen/islandr:latest
# → http://localhost:7080
```

The image runs the full configuration plane; enforcing rules on the host kernel from an unprivileged container uses the `islandr-proxy` socket proxy ([ADR-0012](docs/adr/0012-docker-socket-proxy.md), setup in [docs/install.md](docs/install.md)). A Compose file with both modes is at [docs/install/docker-compose.yml](docs/install/docker-compose.yml).

Full setup (systemd unit, WireGuard config, nftables): [docs/install.md](docs/install.md).

## Prerequisites

| | Dev / CI | Production hub |
|---|---|---|
| Java | 21 (Temurin recommended) | not needed — native binary |
| WireGuard | not needed (mock adapter) | `wg` + `wg-quick` on the hub |
| nftables | not needed (mock adapter) | `nft` on the hub |
| OS | macOS / Linux / Windows (dev only) | Linux x86_64 or ARM64 |
| Database | in-memory SQLite (auto) | SQLite file or PostgreSQL |

## Running it locally

Dev server (Quarkus live coding):

```bash
./gradlew quarkusDev
# → http://localhost:8080
```

The `%dev` profile ships with `islandr.admin.user=admin` / `islandr.admin.password=admin` so the local login just works. **In prod the password has no default** — operators must set `ISLANDR_ADMIN_PASSWORD` as an env var, otherwise `/api/v1/auth/login` returns HTTP 503 ("local admin login disabled"). This is deliberate: a known default in containers is a security hole; a loud failure is not.

Tests (209, runs in ~9 s after warm start):

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
│   ├── prd.md                               # Product Requirements Document
│   ├── install.md                           # Installation guide (native binary, Docker)
│   ├── arc42/                               # Architecture documentation (arc42, 12 chapters)
│   └── adr/                                 # Architecture Decision Records (Nygard + Pugh)
│       ├── README.md                        # ADR index
│       ├── 0001-quarkus-backend.md
│       ├── 0002-vue-without-npm.md
│       ├── 0003-nftables-replaces-ufw.md
│       ├── 0004-sqlite-dev-postgres-prod.md
│       ├── 0005-hub-only-firewall.md
│       ├── 0006-resource-level-acl.md
│       ├── 0007-private-key-retention.md
│       ├── 0008-runtime-settings-in-db.md
│       ├── 0009-license-eupl-1.2.md
│       ├── 0010-font-and-icon-asset-self-hosting.md
│       ├── 0011-process-privilege-model.md
│       ├── 0012-docker-socket-proxy.md
│       ├── 0013-default-everyone-role.md
│       └── 0014-device-discovery.md
├── architecture/
│   ├── workspace.dsl                        # C4 model (Structurizr DSL) — source of diagrams
│   ├── docs/                                # Markdown pages rendered into the interactive C4 site
│   │   ├── 01-overview.md                   # home / entry point of the architecture portal
│   │   └── 02-roadmap.md                    # roadmap page
│   └── diagrams/                            # generated C4 PNGs + .puml, embedded in arc42
├── src/
│   ├── main/java/de/chriscohnen/islandr/
│   │   ├── acl/         # RBAC0: Roles, Resources, Ports/PortGroups, Sites, ACL matrix, "Mein Zugang"
│   │   ├── admin/       # config export/import, version check
│   │   ├── audit/       # audit log (entity, diff, resource, service)
│   │   ├── auth/        # Session, SessionFilter, AdminBootstrap, AuthResource, OidcAuthResource
│   │   ├── crypto/      # EncryptionService — AES-256-GCM for secrets/keys at rest
│   │   ├── dashboard/   # dashboard aggregation (DTO + resource)
│   │   ├── firewall/    # nftables RuleBuilder + adapters (real/mock/dry-run) + RulesetService
│   │   ├── identity/    # OidcProvider, JwksCache, IdTokenVerifier, OidcLoginService, AvatarFetcher
│   │   ├── peer/        # Peer entity + DTO + Resource + Service + IpSubnet + QrService
│   │   ├── settings/    # singleton settings (WG topology, retention mode, hub geocoding)
│   │   ├── user/        # User + Resource + AvatarService + Google Workspace import
│   │   ├── validation/  # @ValidIpAddress / @ValidCidr custom validators
│   │   ├── wg/          # WgAdapter (real shells out, mock for dev/CI)
│   │   └── NativeReflectionConfig.java      # GraalVM native-image reflection registration
│   ├── main/resources/
│   │   ├── application.properties
│   │   ├── db/migration/                    # Flyway migrations V1–V33, portable SQL
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 208 tests, JUnit 5 + RestAssured + AssertJ
```


## Status & roadmap

**Early access — core feature set complete, live production testing in progress.**

### What works today

**Authentication & identity**
- Local admin (ENV-bootstrapped) *and* per-user local passwords (PBKDF2) — no external IdP required
- OIDC: Microsoft 365 / Entra ID and Google, fully GUI-configurable at runtime without a restart; one provider active at a time
- Avatars: MS Graph photo → Google picture → Gravatar (opt-in) → deterministic initials

**Users, peers & devices**
- Users with roles, plus a default **Everyone** role every user belongs to
- Peers: client and site types, IPv4 with optional **IPv6 dual-stack**, IP suggestion, CIDR-overlap validation, per-peer MTU
- Server-side keypairs or admin-imported public keys; **private-key retention** in three modes — `never` (default), `plaintext`, `encrypted` (AES-256-GCM)
- QR code + `.conf` download as a one-time secret; **import existing peers** from a live `wg0`
- Approximate peer location from the endpoint IP; hub location editable in Settings

**Networks, resources & firewall**
- Sites and typed resources (computer, router, printer, NAS, camera, IoT, rack server, KVM host, …)
- **Device discovery** — scan a site's own CIDR for live hosts, identify them by their open ports and reverse DNS, and bulk-create resources from a reviewable list. Unprivileged sockets only, no new capabilities ([ADR-0014](docs/adr/0014-device-discovery.md))
- Resource-level ACL: roles → resource grants, per port, port ranges, or all ports
- nftables ruleset generation with atomic, cold-start-safe reload
- **Docker without `NET_ADMIN`** — unprivileged container plus a host-side socket proxy ([ADR-0012](docs/adr/0012-docker-socket-proxy.md))
- Enforcement state is always visible — direct, via proxy, or degraded. Nothing is ever silently unenforced
- Activity poller and live handshake indicators (last seen, endpoint, rx/tx)

**Self-service portal**
- Users enrol their own devices: platform → QR + `.conf` → first handshake. Key rotation, device list, access overview. Admins can switch it off
- **Quicklaunch** on granted resources: HTTP/HTTPS (with optional path prefix), RDP, VNC, SSH, SFTP, SMB, and IPP printer install via native URI handlers
- **Browser-based RDP** (IronRDP WASM) — no client to install, ACL-gated, with per-port clipboard and file-transfer toggles and an optional `web-only` mode
- Platform-detected WireGuard client setup guide on first visit

**Operations**
- Google Workspace user import (the service-account JSON is encrypted at rest)
- Audit log with cursor pagination and actor/action/target filters
- Config **export/import** as a JSON snapshot, with preview and confirm
- On-demand update check — no telemetry, no background polling
- Bilingual UI, German default and English, switchable at runtime

### Release notes

Only the changes that matter if you actually use it. Earlier versions: [CHANGELOG.md](CHANGELOG.md) ·
binaries, checksums and every change: [GitHub releases](https://github.com/chriscohnen/islandr/releases).

**0.12.1**
- **The UI is now fully bilingual.** The language toggle already existed, but several screens still had German baked in — the peer and device dialogs, the browser-RDP connect flow and its error messages, the topology view, the setup banner, and a number of form hints and placeholders — so switching to English left German behind. Relative times ("vor 3 h") and dates followed the same pattern. Every user-visible string now resolves through the DE/EN catalogue, relative times and dates render in the active language, and the two language sets are at parity. Route URIs stay English in both languages, so links and bookmarks don't change with the toggle.
- **Error messages no longer show a raw key.** Saving a site, loading or saving settings, and applying a port group could fail with the literal text `sites.error_save` (and similar) instead of a real message, because those keys were referenced but never defined. They now read as proper sentences in both languages.

**0.12.0**
- **Device discovery** — stop typing IP addresses. Point the hub at a site and it scans that site's own CIDR, lists the hosts that are actually live, guesses what each one is from its open ports, and hands you a checklist to create resources from in one go. Names come from reverse DNS where available, hosts you already manage are marked as known, the suggested ports can be adopted per host, and a running scan shows live progress and can be aborted. The scan uses ordinary unprivileged sockets — no raw sockets, no new capabilities, no extra `sudoers` entry — and it stays clear of the enforcement path entirely ([ADR-0014](docs/adr/0014-device-discovery.md), [#20](https://github.com/chriscohnen/islandr/issues/20)).
- **Resource list with bulk actions** — resources now have a list view with multi-select and bulk delete, so cleaning up after a discovery run is one action instead of many.
- **Docker no longer pretends to enforce** — a plain `docker run` used to fall back to the in-memory mock adapter, which accepts peer and rule changes and even simulates online peers, so the console looked like it was enforcing while nothing reached the host kernel. The container now runs the real socket adapter and says plainly that enforcement is unavailable until the host proxy is attached. **If you evaluate Islandr in Docker, take this release** ([ADR-0012](docs/adr/0012-docker-socket-proxy.md)).
- **Config import no longer destroys the instance** — importing a config used to leave a database it could not recover from, through two separate faults. The timestamps were written in a format SQLite stored happily but could never read back, so the import reported success and every request touching an imported row then failed with HTTP 500 — peers, users, avatars. And the export silently dropped the auto-membership flag on the "Everyone" role, so the next start tried to create a second one, hit the unique index on the role name, and the application **failed to boot at all** — on that restart and every one after. Both are fixed, and both heal themselves on the next start: the timestamps are normalised in place and the existing "Everyone" role is adopted instead of duplicated. No re-install, no restore from backup. **If you have ever used config import, take this release.**
- **Copy buttons stay inside the enforcement banner** — the long install commands pushed them out of the box at some window widths and zoom levels; the command now scrolls within its own line instead.

Planned features are tracked as GitHub issues — 👍 or comment to signal what matters to you.

**v2 — Usability & convenience** ([milestone](https://github.com/chriscohnen/islandr/milestone/1))
- [Peer expiry / auto-disable](https://github.com/chriscohnen/islandr/issues/10)
- [Multi-site map view](https://github.com/chriscohnen/islandr/issues/11) — sites and live tunnels on a map (Leaflet + OSM, no Google Maps)
- [Entra ID user import](https://github.com/chriscohnen/islandr/issues/12) — browse org users and import selected; the Google Workspace half of this shipped in 0.9.1

**v3 — Operations** ([milestone](https://github.com/chriscohnen/islandr/milestone/2))
- [`.deb` package](https://github.com/chriscohnen/islandr/issues/14) for `apt install islandr` on Ubuntu/Debian
- [API key management](https://github.com/chriscohnen/islandr/issues/15) for automation

## Documentation

- [docs/install.md](docs/install.md) — Installation guide (native binary + systemd, Docker Compose)
- [docs/prd.md](docs/prd.md) — Product Requirements Document
- [docs/adr/](docs/adr/) — Architecture Decision Records (Nygard format, Pugh matrix)
- [docs/arc42/](docs/arc42/) — Architecture documentation (arc42, 12 chapters, C4 diagrams embedded)

## Architecture diagrams

The C4 model lives in [`architecture/workspace.dsl`](architecture/workspace.dsl) (Structurizr DSL). Diagrams are rendered automatically on every push by CI and committed to `architecture/diagrams/` as PNGs, and embedded in the arc42 chapters.

**[Explore the model interactively →](https://islandr-gateway.net/architecture/master/islandr/container/)** — browsable C4 views (context, container, component, deployment), generated from the same DSL and hosted alongside the landing page.

For local editing, the [Structurizr extension for VS Code](https://marketplace.visualstudio.com/items?itemName=systemsarchitect.vscode-structurizr) gives a live preview.

## Contributing & feedback

Bug reports and feature ideas via [GitHub Issues](https://github.com/chriscohnen/islandr/issues). Pull requests are not accepted — see [CONTRIBUTING.md](CONTRIBUTING.md) for why and what works instead.

## License

**EUPL-1.2** (EU-governed, copyleft, AGPL-compatible). See [docs/adr/0009-license-eupl-1.2.md](docs/adr/0009-license-eupl-1.2.md) for the rationale.

## Legal Notice / Trademark Disclaimer

The name **islandr** and the project hosted under `islandr-gateway.net` or this GitHub repository are independent open-source developments by Christian Cohnen.

This project is NOT affiliated, associated, authorized, endorsed by, or in any way officially connected with any other project or company using the name "islandr" or similar, including but not limited to:

- **PerfTech Inc.** and their "Island Router" product (islandrouter.com)
- The **islandr-project.eu** initiative

All product and company names are trademarks™ or registered® trademarks of their respective holders. Use of them does not imply any affiliation with or endorsement by them.

WireGuard® is a registered trademark of Jason A. Donenfeld. Islandr is an independent project, not affiliated with or endorsed by the WireGuard project.
