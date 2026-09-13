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

## How Islandr treats your WireGuard config

**It never writes `/etc/wireguard/<iface>.conf`.** Peers are configured at
runtime with `wg set`; the interface — private key, listen port, `Address`,
`PostUp`/`PostDown` — is created by you before Islandr is installed and stays
yours. The service could not write that file if it wanted to: it runs
unprivileged, with sudo scoped to `nft` and `wg`
([ADR-0011](docs/adr/0011-process-privilege-model.md)).

What that means in practice, both ways round:

- **Installing on a hub that already runs WireGuard changes nothing.** Existing
  peers keep working. Islandr starts with firewall writes paused, so nothing is
  enforced until you switch it on deliberately — import your peers first, and
  the Admin Console warns you if any are still unknown when you do.
- **Peers Islandr manages are kernel state,** so a reboot or a
  `systemctl restart wg-quick@<iface>` brings the interface back with only the
  peers in your file. Islandr re-applies its own at startup, and the activity
  poller repairs the same drift within one tick while it is running — but the
  peer set does depend on the service running.
- **Peers in your file that Islandr has not imported keep connecting,** and
  they reach the hub itself: the generated ruleset filters forwarded traffic,
  not traffic to the hub. The Dashboard reports how many there are; importing
  them is how they become governed.
- **Removing Islandr is survivable.** The Peers view exports every managed peer
  as `[Peer]` blocks to append to your server config — no `[Interface]`
  section, that part stays yours on the way out too.

Full rationale, the alternatives weighed, and why `wg syncconf` is not used:
[ADR-0030](docs/adr/0030-wireguard-config-file-ownership.md).

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
| WireGuard mgmt | `wg` CLI via Java `ProcessBuilder` (real adapter) + in-memory mock adapter for dev/CI |
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
BASE="https://github.com/chriscohnen/islandr/releases/latest/download"
cd /tmp && curl -fsSL -O "$BASE/islandr-runner-linux-${ARCH}" -O "$BASE/islandr-runner-linux-${ARCH}.sha256"
sha256sum -c "islandr-runner-linux-${ARCH}.sha256"
sudo install -d -o islandr -g islandr /opt/islandr
sudo install -o islandr -g islandr -m 0755 "islandr-runner-linux-${ARCH}" /opt/islandr/islandr
```

That is the binary only. [`docs/install/setup-hub.sh`](docs/install/setup-hub.sh) does the whole
thing — service user, scoped sudo, env file, systemd unit — and checks the prerequisites first:

```bash
curl -fsSL https://raw.githubusercontent.com/chriscohnen/islandr/main/docs/install/setup-hub.sh -o setup-hub.sh
less setup-hub.sh && sudo bash setup-hub.sh
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
| WireGuard | not needed (mock adapter) | `wg` (wireguard-tools) on the hub, and an interface that is already up |
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

The test profile uses an in-memory SQLite that's wiped per run (`clean-at-start=true`) and a `MockWgAdapter` so no `wg` binary is needed.

## Repository layout

```
islandr/
├── README.md                                # this file
├── CLAUDE.md                                # guidance for Claude Code
├── build.gradle.kts                         # Gradle 9.1 / Kotlin DSL
├── docs/
│   ├── prd.md                               # Product Requirements Document
│   ├── install.md                           # Installation guide (native binary, Docker)
│   ├── install/                             # setup-hub.sh, reverse-proxy.md, hardening.md, identity-microsoft365.md, fail2ban.md
│   ├── faq.md                               # Operational FAQ (logs, wg/nft troubleshooting)
│   ├── arc42/                               # Architecture documentation (arc42, 12 chapters)
│   └── adr/                                 # Architecture Decision Records (Nygard + Pugh)
│       └── README.md                        # ADR index — one file per decision, numbered 0001+
├── architecture/
│   ├── workspace.dsl                        # C4 model (Structurizr DSL) — source of diagrams
│   ├── docs/                                # Markdown pages rendered into the interactive C4 site
│   │   ├── 01-overview.md                   # home / entry point of the architecture portal
│   │   └── 02-roadmap.md                    # roadmap page
│   └── diagrams/                            # generated C4 PNGs + .puml, embedded in arc42
├── scripts/
│   ├── update.sh                            # download, verify, swap the binary, roll back if it fails
│   ├── backup.sh                            # gzip-compressed, rotated SQLite backup via `sqlite3 .backup`
│   └── check-templates.mjs                  # compiles every inline Vue template in CI (no npm, uses the vendored Vue)
├── src/
│   ├── main/java/de/chriscohnen/islandr/
│   │   ├── acl/         # RBAC0: Roles, Resources, Ports/PortGroups, Sites, ACL matrix, "Mein Zugang", port reservations (#72)
│   │   ├── acme/        # hand-rolled RFC 8555 ACME client — Let's Encrypt auto-provisioning
│   │   ├── admin/       # config export/import, version check
│   │   ├── apikey/      # admin-issued API keys for the external automation API (ADR-0026)
│   │   ├── audit/       # audit log (entity, diff, resource, service)
│   │   ├── auth/        # Session, SessionFilter, AdminBootstrap, AuthResource, OidcAuthResource
│   │   ├── crypto/      # EncryptionService — AES-256-GCM for secrets/keys at rest
│   │   ├── dashboard/   # dashboard aggregation (DTO + resource)
│   │   ├── discovery/   # unprivileged CIDR scan for device discovery (ADR-0014), the link-scope gate that skips mDNS/LLMNR for off-link targets, and MAC/OUI vendor lookup (#76)
│   │   ├── dns/         # hand-rolled DNS wire format: peer-facing resource-name resolver (ADR-0023), plus PTR/mDNS/LLMNR/NetBIOS/SSDP lookups feeding discovery's hostname and MAC suggestions (#45, #48, #76)
│   │   ├── external/    # /api/external/v1 facade: API-key auth, peers/users/sites/resources/roles (ADR-0026)
│   │   ├── firewall/    # nftables RuleBuilder + adapters (real/mock/dry-run) + RulesetService
│   │   ├── hosthealth/  # hub CPU/memory/swap sampler, hand-rolled from /proc (issue #73)
│   │   ├── identity/    # OidcProvider + OidcCustomProvider (issue #69), JwksCache, IdTokenVerifier, OidcLoginService, AvatarFetcher
│   │   ├── network/     # network diagnostics: ping/tracepath/mtr over an unprivileged shell (ADR-0025)
│   │   ├── peer/        # Peer entity + DTO + Resource + Service + IpSubnet + QrService
│   │   ├── proxy/       # Docker socket-proxy client + reconciler (ADR-0012)
│   │   ├── settings/    # singleton settings (WG topology, retention mode, hub geocoding)
│   │   ├── tls/         # built-in TLS termination, cert hot-swap (ADR-0015)
│   │   ├── user/        # User + Resource + AvatarService + Google Workspace import + access expiry (#53)
│   │   ├── validation/  # @ValidIpAddress / @ValidCidr custom validators
│   │   ├── webhook/     # outbound event webhooks (issue #68)
│   │   ├── wg/          # WgAdapter (real shells out, mock for dev/CI)
│   │   └── NativeReflectionConfig.java      # GraalVM native-image reflection registration
│   ├── main/resources/
│   │   ├── application.properties
│   │   ├── data/oui-vendors.csv             # bundled IEEE MA-L registry — MAC prefix → vendor, resolved offline (#76)
│   │   ├── db/migration/                    # Flyway migrations V1–V76, portable SQL
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── api/openapi.yml              # hand-written OpenAPI spec for the external API facade (ADR-0026)
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 903 tests, JUnit 5 + RestAssured + AssertJ
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
- **Device discovery** — scan a site's own CIDR for live hosts, identify them by their open ports, and bulk-create resources from a reviewable list. Discovery also suggests a name from whatever the host is willing to tell it. How much that is varies a lot by device and by network: some answer with a proper hostname, some with a product label, plenty answer nothing at all and keep the typed baseline. It is a head start on filling in a scan result, not an inventory system. Unprivileged sockets only, no new capabilities ([ADR-0014](docs/adr/0014-device-discovery.md), [#45](https://github.com/chriscohnen/islandr/issues/45), [#48](https://github.com/chriscohnen/islandr/issues/48))
- **MAC address and hardware vendor, where the device gives one up** — a resource can carry its MAC, and the vendor ("Ubiquiti Networks", "Raspberry Pi Foundation") is named from a table bundled with the binary, so no lookup leaves the host. Same caveat as the name suggestion: it works for some devices and not others, and less often the further the device sits from the hub. An **Identify** action retries it on demand for a resource that has none ([#76](https://github.com/chriscohnen/islandr/issues/76))
- Resource-level ACL: roles → resource grants, per port, port ranges, or all ports
- **Resource-type ACL grants** — roles → every resource of a type at a site (e.g. "all printers in the home office"), additive to individual grants ([ADR-0022](docs/adr/0022-acl-type-grants.md))
- **Microsoft 365 login is documented end to end** — registering the Entra ID app, the exact permissions Islandr needs and why, and the setup errors that never name their own cause ([docs/install/identity-microsoft365.md](docs/install/identity-microsoft365.md)). The Identity page helps rather than assuming: the redirect URI is copyable with one click (with a fallback for a hub still reached over plain HTTP, which is exactly when an admin is configuring this), and the tenant field is labelled the way Entra labels it instead of the way the protocol does
- **Whole-network grants** — a role can be granted a whole site network at once, covering hosts added later. Deliberately coarse: always full access, no port scoping, and it reaches hosts Islandr has never been told about — use it where the network boundary already is the access boundary ([ADR-0029](docs/adr/0029-whole-network-role-grants.md), [#78](https://github.com/chriscohnen/islandr/issues/78))
- **Direct user→resource grants** — grant one specific user access to a resource without a role, for one-off exceptions that don't warrant a new role ([ADR-0024](docs/adr/0024-direct-user-resource-grants.md))
- **Site-to-site grants** — a site's gateway peer can itself be a grant subject, authorizing the whole site's CIDR (not just individual peers) to reach a resource, full-access or port-scoped ([#52](https://github.com/chriscohnen/islandr/issues/52))
- **Atlas view** — a global map of who/what can reach which resources across the whole tenant. Click a user, resource or site to narrow it down, drag to grant, revoke from the graph itself ([#49](https://github.com/chriscohnen/islandr/issues/49))
- **Network diagnostics from Atlas** — admin-triggered ping, tracepath, and mtr against a resource, a site's gateway peer, or any currently-connected client peer, run hub-side over an unprivileged shell (no `sudo`, no new capabilities). Results dock in a panel beside the graph and overlay the actual probed path (hub → site gateway → target) with live reachability/latency on the diagram itself ([ADR-0025](docs/adr/0025-network-diagnostic-helpers.md), [#66](https://github.com/chriscohnen/islandr/issues/66))
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
- **Connection activity heatmap** — peers × days, coloured by traffic volume rather than plain presence, so a device gone quiet stands out at a glance and a hover shows connection duration or ↓/↑ MB. Site gateways are set apart from client devices, since a quiet day means something different for each
- Google Workspace user import (the service-account JSON is encrypted at rest)
- Audit log with cursor pagination and actor/action/target filters
- Config **export/import** as a JSON snapshot, with preview and confirm
- **External API for automation** — a separate, versioned `/api/external/v1` surface, authenticated by admin-issued API keys (one-time reveal, hashed at rest, instantly revocable) instead of a session cookie; a hand-written OpenAPI spec (`GET /api/openapi.yml`) documents it. Read access to peers, users, sites, resources, and roles today, growing incrementally ([ADR-0026](docs/adr/0026-external-api-facade.md), [#15](https://github.com/chriscohnen/islandr/issues/15))
- On-demand update check — no telemetry, no background polling
- Bilingual UI, German default and English, switchable at runtime

### What's new in 0.22.0

Every version: [CHANGELOG.md](CHANGELOG.md) · binaries and checksums:
[GitHub releases](https://github.com/chriscohnen/islandr/releases).

- **Fail-closed boot firewall** — if Islandr does not start, the hub now forwards nothing instead of forwarding unfiltered. Existing installs need `setup-hub.sh` re-run ([ADR-0031](docs/adr/0031-fail-closed-boot-ruleset.md))
- **Brute-force protection for local logins** — a progressive delay per account and per source address, never a lockout, plus a failure log line fail2ban can match ([docs/install/fail2ban.md](docs/install/fail2ban.md))
- **Trusted reverse proxies are a setting** — name your proxy under Settings → Reverse proxy and failed logins record the real client address, not the proxy's
- **Resource ports can be edited** — correcting a path prefix no longer means deleting the port and its grants
- **Avatar upload** — admins set anyone's picture, users their own; no Gravatar call needed
- **Entra ID setup checks itself** — a test that names the wrong field, and the admin-consent return no longer reports a CSRF error
- **DNS server per network, settable in the scan dialog** — without it a `/24` imports as a column of `computer-42`
- Fixed: browser-RDP refused sessions the portal allowed, the downloaded `.rdp` ignored the port's clipboard setting, and IPv6-only peers were offered for an import that could not succeed

### Roadmap

Planned features are tracked as GitHub issues — 👍 or comment to signal what matters to you.

**v2 — Usability & convenience** ([milestone](https://github.com/chriscohnen/islandr/milestone/1))
- [Entra ID user import](https://github.com/chriscohnen/islandr/issues/12) — browse org users and import selected; the Google Workspace half of this shipped in 0.9.1

**v3 — Operations** ([milestone](https://github.com/chriscohnen/islandr/milestone/2))
- [`.deb` package](https://github.com/chriscohnen/islandr/issues/14) for `apt install islandr` on Ubuntu/Debian

## Documentation

- [docs/install.md](docs/install.md) — Installation guide (native binary + systemd, Docker Compose)
- [docs/install/hardening.md](docs/install/hardening.md) — why the systemd unit and sudoers file look the way they do
- [docs/install/identity-microsoft365.md](docs/install/identity-microsoft365.md) — registering the Entra ID app, the permissions Islandr needs, and the setup errors that do not name their cause
- [docs/install/fail2ban.md](docs/install/fail2ban.md) — the built-in login backoff, the log line fail2ban matches, and why banning your own reverse proxy is the easy mistake
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

What you may and may not do with the Islandr name and logo is set out in [TRADEMARK.md](TRADEMARK.md) — short version: the EUPL covers the code, not the marks, and a fork needs its own name.

The name **islandr** and the project hosted under `islandr-gateway.net` or this GitHub repository are independent open-source developments by Christian Cohnen.

This project is NOT affiliated, associated, authorized, endorsed by, or in any way officially connected with any other project or company using the name "islandr" or similar, including but not limited to:

- **PerfTech Inc.** and their "Island Router" product (islandrouter.com)
- The **islandr-project.eu** initiative

All product and company names are trademarks™ or registered® trademarks of their respective holders. Use of them does not imply any affiliation with or endorsement by them.

WireGuard® is a registered trademark of Jason A. Donenfeld. Islandr is an independent project, not affiliated with or endorsed by the WireGuard project.
