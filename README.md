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

Tests (177, runs in ~9 s after warm start):

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
│       ├── 0010-font-and-icon-asset-self-hosting.md
│       └── 0011-process-privilege-model.md
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
│   │   ├── db/migration/                    # Flyway migrations V1–V21, portable SQL
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 177 tests, JUnit 5 + RestAssured + AssertJ
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
- **IPv6 dual-stack peers** — optional `assignedIpv6` per peer; nftables rules emit `ip`/`ip6` per address family; custom `@ValidIpAddress`/`@ValidCidr` validators replace regex patterns
- Per-peer MTU override (default 1420)
- Reverse geocoding — approximate peer location derived from endpoint IP
- Server-side keypair generation or admin-imported public key (validated via `wg pubkey`)
- **Private key retention** — three modes: `never` (default), `plaintext`, `encrypted` (AES-256-GCM, server-side master key)
- QR code + `.conf` download with one-time-secret pattern; re-show in `plaintext` / `encrypted` retention mode
- **Import peers from live wg0** — reads `wg show wg0 dump`, compares by public key, lets admin select and name unmanaged peers in one step

**Networks, resources & firewall**
- Sites and resources with typed resource cards (computer, router, printer, NAS, camera, IoT, virt-host, management)
- Port groups and resource-level ACL: roles → resource grants, per-port, port ranges (`8080-8090`), or all-ports mode
- ACL matrix shows granted vs. total port count per cell (e.g. `1/2`)
- nftables ruleset generation — atomic reload via RuleBuilder, cold-start-safe, mock adapter for dev/CI
- Activity poller: last seen, last endpoint, bytes-counter delta (rx/tx)
- Connectivity indicators: live handshake dot (●/○) in Peers and Networks views; dashboard topology colors gateway ring green/muted and shows gateway peer IP + handshake age on hover

**Self-service portal**
- End users add their own devices via a 3-step flow: platform → QR + `.conf` → wait for first handshake
- Key rotation, device list, accessible resource overview with protocol icons
- Admin toggle to disable self-service peer creation (stricter environments)
- Protocol quicklaunch on granted resources:
  - **HTTP/HTTPS** — opens directly in the browser
  - **RDP** — `.rdp` file download (Windows/macOS) + `rdp://` URI (Linux/Remmina)
  - **VNC** — `vnc://` URI link (RFC 7869; opens Remmina, GNOME Connections, RealVNC)
  - **SSH** — `ssh://` URI (macOS Terminal, Linux terminal emulators)
  - **SFTP** — `sftp://` URI (Nautilus, Dolphin file manager)
  - **SMB** — `smb://` URI (Finder, Nautilus; Windows uses `\\host\share` natively)
  - **IPP printer quick-install** — `ipp://` URI opens native OS print dialog (macOS, Windows, Linux/CUPS)
- **WireGuard client setup guide** — platform-detected install links on first visit; Passepartout recommended for macOS/iOS; Linux commands include one-click copy
- **Config export/import** — full DB snapshot as JSON (GET `/api/v1/admin/config/export`); FK-aware transactional import with preview and confirm step; optional private key inclusion

**Observability**
- Audit log with cursor-based pagination, actor/action/target filters, meta-JSON expand
- Update check — Settings shows the running version with an on-demand button to check GitHub for a newer release; you stay current without leaving the console. No background polling, no telemetry — the check only runs when you click it

**Bilingual UI**
- German (default) and English, switchable at runtime without reload

Planned features are tracked as GitHub issues — 👍 or comment to signal what matters to you. Items already delivered in 0.9.0 (full WireGuard client setup guide, config export/import, IPv6 dual-stack) are listed under [What works today](#what-works-today).

**v2 — Usability & convenience** ([milestone](https://github.com/chriscohnen/islandr/milestone/1))
- [Peer expiry / auto-disable](https://github.com/chriscohnen/islandr/issues/10)
- [Multi-site map view](https://github.com/chriscohnen/islandr/issues/11) — sites and live tunnels on a map (Leaflet + OSM, no Google Maps)
- [Google Workspace / Entra ID user import](https://github.com/chriscohnen/islandr/issues/12) — browse org users, import selected
- [Docker production support](https://github.com/chriscohnen/islandr/issues/13) — unprivileged container via Unix socket proxy, no `NET_ADMIN` required ([ADR-0012](docs/adr/0012-docker-socket-proxy.md))

**v3 — Operations** ([milestone](https://github.com/chriscohnen/islandr/milestone/2))
- [`.deb` package](https://github.com/chriscohnen/islandr/issues/14) for `apt install islandr` on Ubuntu/Debian
- [API key management](https://github.com/chriscohnen/islandr/issues/15) for automation

## Documentation

- [docs/install.md](docs/install.md) — Installation guide (native binary + systemd, Docker Compose)
- [docs/prd.md](docs/prd.md) — Product Requirements Document
- [docs/adr/](docs/adr/) — Architecture Decision Records (Nygard format, Pugh matrix)
- [docs/arc42/](docs/arc42/) — Architecture documentation (arc42, 12 chapters, C4 diagrams embedded)

## Architecture diagrams

The C4 model lives in [`architecture/workspace.dsl`](architecture/workspace.dsl) (Structurizr DSL). Diagrams are rendered automatically on every push by CI and committed to `architecture/diagrams/` as PNGs. They are embedded in the arc42 chapters.

To explore the model interactively:

1. Open [`architecture/workspace.dsl`](architecture/workspace.dsl) and copy its contents.
2. Paste into the [Structurizr DSL editor (playground)](https://structurizr.com/dsl) — the playground has no URL-loading API, copy-paste is the only option.
3. Alternatively, install the [Structurizr extension for VS Code](https://marketplace.visualstudio.com/items?itemName=systemsarchitect.vscode-structurizr) for local live preview.

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
