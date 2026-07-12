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

The image runs the full configuration plane; enforcing rules on the host kernel from an unprivileged container needs the socket proxy (planned, [ADR-0012](docs/adr/0012-docker-socket-proxy.md)). A Compose file with both modes is at [docs/install/docker-compose.yml](docs/install/docker-compose.yml).

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
│       └── 0012-docker-socket-proxy.md
├── architecture/
│   ├── workspace.dsl                        # C4 model (Structurizr DSL) — source of diagrams
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
- Local admin login (ENV-bootstrapped, SHA-256 constant-time, 12h revocable sessions)
- OIDC: Microsoft 365 / Entra ID and Google — full code-exchange + JWKS-cached RS256 verification, all config GUI-editable at runtime without restart
- At most one OIDC provider active at a time; admin can swap via confirm dialog
- Avatar pipeline: MS Graph photo → Google picture claim → Gravatar (opt-in) → deterministic initials fallback

**User & peer management**
- User CRUD with admin/end-user role assignment
- Peer CRUD: client and site peer types, IP suggestion from WG subnet, CIDR-overlap validation
- **IPv6 dual-stack peers** — optional `assignedIpv6` per peer; nftables rules emit `ip`/`ip6` per address family; custom `@ValidIpAddress`/`@ValidCidr` validators replace regex patterns
- Per-peer MTU override (default 1420)
- Reverse geocoding — approximate peer location derived from endpoint IP; hub location label editable in Settings with geocoding assist
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
  - **HTTP/HTTPS** — opens directly in the browser; optional per-port path prefix (e.g. `/admin`) so multi-app hosts work without a dedicated port per app
  - **RDP** — `.rdp` file download (Windows/macOS) + `rdp://` URI (Linux/Remmina) + **browser-based RDP** via IronRDP WASM (no client install); `web-only` access mode blocks direct WireGuard port access so users are forced through the browser proxy; per-port clipboard and file-transfer toggles; global enable toggle in Settings
  - **VNC** — `vnc://` URI link (RFC 7869; opens Remmina, GNOME Connections, RealVNC)
  - **SSH** — `ssh://` URI (macOS Terminal, Linux terminal emulators)
  - **SFTP** — `sftp://` URI (Nautilus, Dolphin file manager)
  - **SMB** — `smb://` URI (Finder, Nautilus; Windows uses `\\host\share` natively)
  - **IPP printer quick-install** — `ipp://` URI opens native OS print dialog (macOS, Windows, Linux/CUPS)
- **WireGuard client setup guide** — platform-detected install links on first visit; Passepartout recommended for macOS/iOS; Linux commands include one-click copy
- **Config export/import** — full DB snapshot as JSON (GET `/api/v1/admin/config/export`); FK-aware transactional import with preview and confirm step; optional private key inclusion

**Google Workspace integration**
- Import users from a GWS directory — browse org users, see who is already in Islandr, import selected; configurable via OAuth service account in Settings

**Observability**
- Audit log with cursor-based pagination, actor/action/target filters, meta-JSON expand
- Update check — Settings shows the running version with an on-demand button to check GitHub for a newer release; you stay current without leaving the console. No background polling, no telemetry — the check only runs when you click it

**Bilingual UI**
- German (default) and English, switchable at runtime without reload

### Release notes

Only the changes that matter if you actually use it — see the [GitHub releases](https://github.com/chriscohnen/islandr/releases) for the full list.

**0.11.0**
- **Docker without `NET_ADMIN`** — run the hub as an unprivileged container. A small host-side proxy (`islandr-proxy`) owns the WireGuard and nftables commands, and the container talks to it over a Unix socket, so it no longer needs broad host privileges. If the proxy is unreachable the hub stays up in a clearly-flagged degraded mode (peers and ACLs are managed, enforcement is paused) with a banner in the admin console instead of failing hard. The socket-proxy setup is documented in [docs/install.md](docs/install.md) ([ADR-0012](docs/adr/0012-docker-socket-proxy.md), [#13](https://github.com/chriscohnen/islandr/issues/13)).
- **Enforcement mode in Settings** — Settings now shows whether the hub is enforcing rules directly, through the socket proxy, or running degraded, so you can tell at a glance what is actually applying your ACLs.
- **A default "Everyone" role** — every user is automatically a member, so shared resources can be granted once to Everyone instead of per user or per group. Auto-managed; you cannot accidentally remove someone from it ([ADR-0013](docs/adr/0013-default-everyone-role.md)).
- **More resource types** — rack server and KVM/virtualisation host join the resource catalogue (with fitting icons), and you can switch an existing resource to them. New resources also adopt their site's CIDR and sensible port defaults, so there is less to type.
- **Configurable WireGuard interface** — set `ISLANDR_WG_INTERFACE` to run on a non-default interface name instead of the built-in one.
- **Polish** — edit a local user's name *and* email; the config-import file picker and the "Everyone" role description now follow the selected UI language; the ACL page uses a master-detail site list so a large network count no longer forces horizontal scrolling.

**0.10.0**
- **Browser-based RDP** — open a granted RDP resource straight from the self-service portal, with no local client to install. An IronRDP WASM client runs in the browser and the hub proxies the connection over a WebSocket, gated by the resource ACL (the target is derived from the database, not the request). Per-port clipboard and file-transfer toggles; a `web-only` access mode blocks the direct WireGuard port so users are forced through the auditable browser proxy. Off by default — enable it globally in Settings.
- **Local users with passwords** — you no longer need an external IdP to hand out logins. An admin sets a per-user password (PBKDF2-hashed, never stored or returned in plaintext) and that user signs in with email + password, alongside or instead of Microsoft/Google OIDC.
- **Usable bootstrap admin** — a fresh install seeds an `admin@local` user and binds the `ISLANDR_ADMIN_PASSWORD` login to it, so you can immediately own a peer and assign yourself roles instead of logging in as a rights-less admin.
- **Onboarding polish** — the port form pre-fills the default port per protocol (RDP 3389, SSH 22, HTTPS 443, …); a peer can be created with no users yet as a site gateway (the client type is disabled with a hint to add a user first); networks hint when there is no gateway peer to pick; the ACL matrix lets you grant "all ports" on single-port resources too; and the dashboard shows your configured hub location name instead of a generic "Hub".
- **No more stale UI after an update** — the front end is revalidated on each load, so a new release shows up without a manual hard-refresh.

**0.9.2 – 0.9.4** — Fixes only, no new features: the Docker image and native binary now boot on a plain `docker run` and on CPU-restricted hosts (e.g. a Proxmox VM on the default CPU model). If you deploy with Docker, install **0.9.4 or later**.

**0.9.1**
- **Path prefix for HTTP/HTTPS resources** — a resource port can carry an optional URL path (e.g. `/admin`), so the portal's quicklaunch opens `https://host/admin` instead of just the host root.
- **Hub coordinates** — set the gateway's own location (latitude/longitude + label) in Settings, by entering coordinates or geocoding an address, so the hub shows up where it really is instead of being guessed from its IP.
- **Settings page restructured** into clearer sections.
- **Google Workspace user import** — browse your org's users and import the ones you pick; the service-account JSON is encrypted at rest.
- Update check moved into Settings with a corrected semver comparison.

**0.9.0**
- **Protocol quicklaunch in the self-service portal** — granted resources open directly: HTTP/HTTPS in the browser, plus RDP, VNC, SSH, SFTP, SMB and IPP printer install via native URI handlers.
- **WireGuard client setup guide** — platform-detected install links for new users on first visit.
- **IPv6 dual-stack peers** — optional IPv6 address per peer alongside IPv4.
- **Encrypted private-key retention** — optionally keep keys server-side under AES-256-GCM so a config can be re-shown later, or stay key-less (the default).
- **Config export/import** — full snapshot as JSON with a preview-and-confirm import.
- **Per-peer MTU override** and **reverse geocoding** of a peer's approximate location from its endpoint IP.
- **Update check** — Settings shows the running version and checks GitHub for a newer release on demand (no telemetry, no background polling).

Planned features are tracked as GitHub issues — 👍 or comment to signal what matters to you.

**v2 — Usability & convenience** ([milestone](https://github.com/chriscohnen/islandr/milestone/1))
- [Peer expiry / auto-disable](https://github.com/chriscohnen/islandr/issues/10)
- [Multi-site map view](https://github.com/chriscohnen/islandr/issues/11) — sites and live tunnels on a map (Leaflet + OSM, no Google Maps)
- [Google Workspace / Entra ID user import](https://github.com/chriscohnen/islandr/issues/12) — browse org users, import selected

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
