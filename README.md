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

<p align="center">
  <img src="https://islandr-gateway.net/screenshots/light/dashboard.png" width="49%" alt="Dashboard: live topology diagram, peers, sites and networks">
  <img src="https://islandr-gateway.net/screenshots/light/worldmap.png" width="49%" alt="World-map view: sites and gateways plotted on a geocoded map">
</p>
<p align="center">
  <img src="https://islandr-gateway.net/screenshots/light/heatmap.png" width="49%" alt="Connection activity heatmap: peers × days, coloured by traffic volume">
  <img src="https://islandr-gateway.net/screenshots/light/self-service.png" width="49%" alt="Self-service portal: employees enrol their own devices">
</p>
<p align="center">
  <img src="https://islandr-gateway.net/screenshots/light/atlas.png" width="49%" alt="Atlas view: global reachability graph, drag-to-grant access by role or by site">
  <img src="https://islandr-gateway.net/screenshots/light/acl.png" width="49%" alt="ACL matrix: role × resource access, port-level">
</p>

---

## Who is this for?

Small teams, homelabs, and remote-first setups that want **sovereign WireGuard access management** without a SaaS control plane:

- Your ISP gives you CG-NAT and no fixed IPv4
- Your router has no WireGuard server mode (older Fritz!Box, or you don't want it there)
- You need more than one site — home, office, lab — with central user and ACL management
- You want employees to self-service their own device configs, not email you for a `.conf` file
- Data sovereignty matters — no connection metadata leaving your network

Islandr configures the **native WireGuard client** on every device — no proprietary client app to install, trust, or audit. And it's **hub-and-spoke by design**: every peer connects through one central gateway, so access control is enforced in one place instead of punching holes between every pair of sites and hoping the firewall rules stay in sync.

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
| QR codes | zxing-core only (PNG in-memory, no AWT dependency, native-image-safe) |
| Firewall | nftables via `nft` CLI — RuleBuilder + atomic reload + mock adapter for dev/CI |
| Deployment | systemd + Quarkus native binary (GraalVM), optional Docker Compose |
| TLS | Built-in termination (dummy cert until you upload your own, hot-swapped at runtime) — Caddy/Let's Encrypt at the edge remains an option |

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

Tests (580+, runs in ~25 s after warm start):

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
│   ├── faq.md                               # Operational FAQ (logs, wg/nft troubleshooting)
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
│       ├── 0014-device-discovery.md
│       ├── 0015-builtin-tls-termination.md
│       ├── 0016-peer-activity-heatmap-storage.md
│       ├── 0017-split-tunnel-network-scope.md
│       ├── 0018-websocket-tunnel-fallback.md
│       ├── 0019-acme-hand-rolled-client.md
│       ├── 0020-dns01-challenge-with-manual-mode.md
│       ├── 0021-topology-world-map.md
│       ├── 0022-acl-type-grants.md
│       └── 0023-resource-dns-resolver-hand-rolled.md
├── architecture/
│   ├── workspace.dsl                        # C4 model (Structurizr DSL) — source of diagrams
│   ├── docs/                                # Markdown pages rendered into the interactive C4 site
│   │   ├── 01-overview.md                   # home / entry point of the architecture portal
│   │   └── 02-roadmap.md                    # roadmap page
│   └── diagrams/                            # generated C4 PNGs + .puml, embedded in arc42
├── scripts/
│   ├── update.sh                            # download, verify, swap the binary, restart the service
│   └── backup.sh                            # gzip-compressed, rotated SQLite backup via `sqlite3 .backup`
├── src/
│   ├── main/java/de/chriscohnen/islandr/
│   │   ├── acl/         # RBAC0: Roles, Resources, Ports/PortGroups, Sites, ACL matrix, "Mein Zugang"
│   │   ├── acme/        # hand-rolled RFC 8555 ACME client — Let's Encrypt auto-provisioning
│   │   ├── admin/       # config export/import, version check
│   │   ├── audit/       # audit log (entity, diff, resource, service)
│   │   ├── auth/        # Session, SessionFilter, AdminBootstrap, AuthResource, OidcAuthResource
│   │   ├── crypto/      # EncryptionService — AES-256-GCM for secrets/keys at rest
│   │   ├── dashboard/   # dashboard aggregation (DTO + resource)
│   │   ├── discovery/   # unprivileged CIDR scan for device discovery (ADR-0014)
│   │   ├── dns/         # hand-rolled DNS resolver for resource names, opt-in (ADR-0023)
│   │   ├── firewall/    # nftables RuleBuilder + adapters (real/mock/dry-run) + RulesetService
│   │   ├── identity/    # OidcProvider, JwksCache, IdTokenVerifier, OidcLoginService, AvatarFetcher
│   │   ├── peer/        # Peer entity + DTO + Resource + Service + IpSubnet + QrService
│   │   ├── proxy/       # Docker socket-proxy client + reconciler (ADR-0012)
│   │   ├── settings/    # singleton settings (WG topology, retention mode, hub geocoding)
│   │   ├── tls/         # built-in TLS termination, cert hot-swap (ADR-0015)
│   │   ├── user/        # User + Resource + AvatarService + Google Workspace import
│   │   ├── validation/  # @ValidIpAddress / @ValidCidr custom validators
│   │   ├── wg/          # WgAdapter (real shells out, mock for dev/CI)
│   │   └── NativeReflectionConfig.java      # GraalVM native-image reflection registration
│   ├── main/resources/
│   │   ├── application.properties
│   │   ├── db/migration/                    # Flyway migrations V1–V62, portable SQL
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 580+ tests, JUnit 5 + RestAssured + AssertJ
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
- **Admin-triggered key rotation** — regenerate a peer's keypair in place for compromised-device response, instead of deleting and recreating the peer; explicit confirmation required, rotation timestamps tracked separately for key and PSK ([#46](https://github.com/chriscohnen/islandr/issues/46))
- **Peer-Scheduler** — a recurring weekly time window that auto-enables/disables a peer, plus a terminal `validUntil` expiry that disables it for good regardless of any open window — closes the long-requested "contractor/trial device shouldn't need an admin to remember to remove it" ([#47](https://github.com/chriscohnen/islandr/issues/47), closes [#10](https://github.com/chriscohnen/islandr/issues/10))
- **Tri-state connection status** — Connected / Stale / Disconnected badges with absolute time thresholds, instead of a binary online/offline read of the last handshake
- Approximate peer location from the endpoint IP; hub location editable in Settings

**Networks, resources & firewall**
- Sites and typed resources (computer, router, printer, NAS, camera, IoT, rack server, KVM host, …)
- **Device discovery** — scan a site's own CIDR for live hosts, identify them by their open ports and reverse DNS, and bulk-create resources from a reviewable list. Unprivileged sockets only, no new capabilities ([ADR-0014](docs/adr/0014-device-discovery.md))
- Resource-level ACL: roles → resource grants, per port, port ranges, or all ports
- **Resource-type ACL grants** — roles → every resource of a type at a site (e.g. "all printers in the home office"), additive to individual grants ([ADR-0022](docs/adr/0022-acl-type-grants.md))
- **Direct user→resource grants** — grant one specific user access to a resource without a role, for one-off exceptions that don't warrant a new role ([ADR-0024](docs/adr/0024-direct-user-resource-grants.md))
- **Site-to-site grants** — a site's gateway peer can itself be a grant subject, authorizing the whole site's CIDR (not just individual peers) to reach a resource, full-access or port-scoped ([#52](https://github.com/chriscohnen/islandr/issues/52))
- **Atlas view** — a global map of who/what can reach which resources across the whole tenant, with click-to-focus filtering and drag-to-grant creation (drag either end: user/site → resource or resource → site) ([#49](https://github.com/chriscohnen/islandr/issues/49))
- **World-map topology view** — sites, gateways and live tunnels on a geocoded map, alongside the existing network diagram ([#11](https://github.com/chriscohnen/islandr/issues/11), [ADR-0021](docs/adr/0021-topology-world-map.md))
- **DNS resolver for resource names** — opt-in, hand-rolled UDP/TCP resolver authoritative for the managed resource zone (per-site subdomains), ACL-filtered per querying peer, everything else forwarded upstream unparsed ([ADR-0023](docs/adr/0023-resource-dns-resolver-hand-rolled.md))
- **Dashboard traffic-tier topology** — network/topology links colour by actual traffic volume, not just handshake recency
- nftables ruleset generation with atomic, cold-start-safe reload
- **Docker without `NET_ADMIN`** — unprivileged container plus a host-side socket proxy ([ADR-0012](docs/adr/0012-docker-socket-proxy.md))
- Enforcement state is always visible — direct, via proxy, or degraded. Nothing is ever silently unenforced
- Activity poller and live handshake indicators (last seen, endpoint, rx/tx)

**Self-service portal**
- Users enrol their own devices: platform → QR + `.conf` → first handshake. Key rotation, device list, access overview. Admins can switch it off
- **Own topology, geo-map, and activity heatmap** — the same visualisations the admin dashboard has, scoped to what the logged-in user can actually see; the heatmap uses a GitHub-contributions layout (weekday × week) instead of the admin's peers × days table ([#43](https://github.com/chriscohnen/islandr/issues/43))
- **Quicklaunch** on granted resources: HTTP/HTTPS (with optional path prefix), RDP, VNC, SSH, SFTP, SMB, and IPP printer install via native URI handlers
- **Browser-based RDP** (IronRDP WASM) — no client to install, ACL-gated, with per-port clipboard and file-transfer toggles and an optional `web-only` mode
- Platform-detected WireGuard client setup guide on first visit

**Operations**
- **Built-in TLS termination** — starts on a placeholder certificate, hot-swaps to your uploaded one at runtime, no reverse proxy required ([ADR-0015](docs/adr/0015-builtin-tls-termination.md))
- **Automatic Let's Encrypt certificates** — set a domain and islandr requests, installs, and renews the certificate itself via a hand-rolled ACME client ([ADR-0019](docs/adr/0019-acme-hand-rolled-client.md))
- **DNS-01 challenge** as an alternative to HTTP-01, including a manual no-API-token mode for registrars without a supported DNS API ([ADR-0020](docs/adr/0020-dns01-challenge-with-manual-mode.md), [#41](https://github.com/chriscohnen/islandr/issues/41))
- **CSR generation for the Origin Certificate** — generate a private key + certificate signing request in-app instead of shelling out to `openssl` ([#42](https://github.com/chriscohnen/islandr/issues/42))
- **Connection activity heatmap** — peers × days, coloured by traffic volume rather than plain presence, so a device gone quiet stands out at a glance and a hover shows connection duration or ↓/↑ MB
- Google Workspace user import (the service-account JSON is encrypted at rest)
- Audit log with cursor pagination and actor/action/target filters
- Config **export/import** as a JSON snapshot, with preview and confirm
- On-demand update check — no telemetry, no background polling
- Bilingual UI, German default and English, switchable at runtime

### Release notes

Only the changes that matter if you actually use it. Earlier versions: [CHANGELOG.md](CHANGELOG.md) ·
binaries, checksums and every change: [GitHub releases](https://github.com/chriscohnen/islandr/releases).

**0.17.0**
- **Peer-Scheduler** — recurring weekly time windows that auto-enable/disable a peer, plus a terminal `validUntil` expiry that disables it for good regardless of any open window; manual enable/disable holds until the next schedule transition instead of being silently overwritten ([#47](https://github.com/chriscohnen/islandr/issues/47), closes [#10](https://github.com/chriscohnen/islandr/issues/10))
- **Site-to-site grants** — a site's gateway peer can itself be granted access to a resource, authorizing the whole site's CIDR at once instead of only individual peers, full-access or port-scoped ([#52](https://github.com/chriscohnen/islandr/issues/52))
- **Atlas view** — a global graph of who/what can reach which resources, with role/direct grant-mode toggling, click-to-focus filtering, and drag-to-grant creation (from a user or a site onto a resource, or a resource onto a site) ([#49](https://github.com/chriscohnen/islandr/issues/49))
- **Direct user→resource grants** — bypass roles for a one-off exception grant to a single user ([ADR-0024](docs/adr/0024-direct-user-resource-grants.md), [#50](https://github.com/chriscohnen/islandr/issues/50))
- **Keyboard shortcuts** — Escape closes modals, `/` focuses search, Ctrl/Cmd+S applies pending ACL matrix changes ([#51](https://github.com/chriscohnen/islandr/issues/51))

**0.16.0**
- **Admin-triggered key rotation** — regenerate a peer's keypair for compromised-device response instead of deleting and recreating the peer; explicit confirmation required, key and PSK rotation timestamps tracked independently ([#46](https://github.com/chriscohnen/islandr/issues/46))
- **DNS resolver for resource names** — opt-in resolver authoritative for the managed resource zone, per-site subdomains, ACL-filtered answers, everything else forwarded upstream unparsed ([ADR-0023](docs/adr/0023-resource-dns-resolver-hand-rolled.md))
- **Tri-state peer connection status** — Connected / Stale / Disconnected with absolute time thresholds, replacing the binary online/offline read
- **Self-service portal gets its own topology, geo-map, and activity heatmap**, scoped to what the logged-in user can see ([#43](https://github.com/chriscohnen/islandr/issues/43))
- **Dashboard topology and geo-map links now colour by traffic volume**, not just handshake recency
- **Reverse-proxy vs. built-in TLS install guide** — side-by-side decision guide for picking between Islandr's built-in TLS and fronting it with Caddy/Traefik/nginx/a CDN ([docs/install/reverse-proxy.md](docs/install/reverse-proxy.md))
- SQLite backup script

Planned features are tracked as GitHub issues — 👍 or comment to signal what matters to you.

**v2 — Usability & convenience** ([milestone](https://github.com/chriscohnen/islandr/milestone/1))
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
