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
> **1.2.0 is out — mostly maintenance.** What is new is small and makes what you already have easier to read, plus a handful of long-standing rough edges gone: the peers list says at a glance whether a device is alive and how long it has been connected, its rows are a third of the height they were, the dashboard counts the bytes of the last hour, and the topology diagram stays legible when you drill into a crowded network. **Edge hubs** — a second hub at a remote site — are groundwork in this release and **not usable yet**: they need software at the edge that fetches its configuration from the hub and enforces the firewall there, and that does not exist. Islandr still drives WireGuard and nftables directly (`wg set`, `ip link`, `nft`), so read the release notes before upgrading regardless.
> Feedback is what moved it here, and it is still what moves it on. If you hit a rough edge, [open an issue](https://github.com/chriscohnen/islandr/issues).

<p align="center">
  <img src="https://islandr-gateway.net/screenshots/light/dashboard.png" width="49%" alt="Dashboard: live topology diagram, peers, sites and networks">
  <img src="https://islandr-gateway.net/screenshots/light/worldmap.png" width="49%" alt="World-map view: sites and gateways plotted on a geocoded map">
</p>
<p align="center">
  <img src="https://islandr-gateway.net/screenshots/light/heatmap.png" width="49%" alt="Connection activity heatmap: peers × days, coloured by traffic volume">
  <img src="https://islandr-gateway.net/screenshots/light/self-service.png" width="49%" alt="Self-service portal: employees enrol their own devices">
</p>

---

## Who is this for?

Small teams, homelabs, and remote-first setups that want **sovereign WireGuard access management** without a SaaS control plane:

- Your ISP gives you CG-NAT and no fixed IPv4
- Your router has no WireGuard server mode (older Fritz!Box, or you don't want it there)
- You need more than one site — home, office, lab — with central user and ACL management
- You want employees to self-service their own device configs, not email you for a `.conf` file
- Data sovereignty matters — no connection metadata leaving your network

Islandr configures the **native WireGuard client** on every device — no proprietary client app to install, trust, or audit. And it's **hub-and-spoke by design**: a device connects to one gateway and reaches everything through it, so access control is enforced where the traffic passes instead of punching holes between every pair of sites and hoping the firewall rules stay in sync. A distant site that should keep its traffic local is what edge hubs are being built for; 1.2.0 carries the model, not yet the edge software (see below).

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

A site that is far away — another continent, a bad route — should not send its local
traffic to the hub and back. **Edge hubs** are being built for that: a second hub at the
site, with devices dialling it instead of crossing the world to reach a printer in the
next room, while policy, users and the audit log stay on the central hub. The model is
in 1.2.0 and the edge software is not, so this is not something to deploy yet.

```
[Road Warrior]──┐
[Home Office]───┼──wg peer──► [Hub VM / Islandr] ◄──wg peer──[UCG site]
[Mobile]────────┘                      │
                                  HTTPS│  rules, keys, status
                                       ▼
[Devices on site]──wg peer──► [Edge hub, remote site]
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
- **Peers Islandr manages are kernel state,** so the peer set depends on the
  service running. It re-applies its own peers at startup and repairs the same
  drift within one poller tick.
- **Peers in your file that Islandr has not imported keep connecting,** and
  reach the hub itself — the ruleset filters forwarded traffic, not traffic to
  the hub. The Dashboard says how many; importing them is how they become
  governed.
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

## What you actually run

One native binary. No JVM to install, no Node runtime, no `node_modules`, no
reverse proxy required — and **nothing that calls home**: no telemetry, no
background polling, no third-party asset loaded from the browser. The update
check runs only when you ask for it.

| | |
|---|---|
| Install | a single ~40 MB binary, or the container image |
| Database | SQLite file, or PostgreSQL |
| TLS | built in, with automatic Let's Encrypt — a reverse proxy stays optional |
| Privileges | runs unprivileged; `sudo` scoped to `nft` and `wg` ([ADR-0011](docs/adr/0011-process-privilege-model.md)) |

Built with Quarkus and Java 21, compiled ahead of time with GraalVM; the
frontend is Vue 3 served as plain ES modules with no build step. None of that
is anything you have to install or maintain — the reasoning is in
[docs/adr/](docs/adr/).

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

Every release binary carries build provenance, so you can check that the file you
downloaded is the one this repository built, rather than trusting the checksum
published beside it:

```bash
gh attestation verify "islandr-runner-linux-${ARCH}" --repo chriscohnen/islandr
```

Needs the GitHub CLI signed in, so this belongs on the machine you download
from rather than on the hub.

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

## Status & roadmap

**1.2.0 — mostly maintenance: less friction in the console.**
Most of this release is the admin console getting out of the way, and what is new is
small — figures and history the hub was already collecting, now shown where the question
gets asked. The peers list used to show a timestamp where the actual question is "is
this device alive, and how steadily": it now carries 14 days of activity per row, says
how long a connected device has been connected, and sorts by last handshake. Its rows are a third of the height they were,
because four labelled buttons used to wrap into three lines at anything under a wide
screen. The dashboard counts the bytes of the last hour, the topology diagram stays
legible when you drill into a crowded network, and the update check notices when the
`update.sh` kept on the hub is older than the release it would install.

Also fixed: an admin viewing someone else's access could add a device there and get it
themselves, a WireGuard subnet with no room for a single host was accepted at the
settings page and failed later at the first peer, and every warning banner in seven
screens named a stylesheet class that does not exist, so none of them had ever been
amber.

**Edge hubs are groundwork, not a feature you can use.** A second hub at a remote site
is modelled, configurable and drawn — a device belongs to exactly one hub, its
configuration points at that hub, and the central hub leaves an edge's devices alone.
What does not exist is the edge itself: software that fetches its peers and rules from
the hub over the API and enforces them locally. Until that lands, an edge is a row in a
database and a WireGuard interface you maintain by hand. Switch it on to model your
sites; do not plan an office around it.

The full feature inventory — everything that works today, grouped by area —
lives in [docs/features.md](docs/features.md).

### What's new in 1.2.0

Full notes: [CHANGELOG.md](CHANGELOG.md) · binaries and checksums:
[GitHub releases](https://github.com/chriscohnen/islandr/releases).

- **The peers list answers its own question** — 14 days of activity per row, how long a connected device has been connected, and sorting by last handshake
- **Its rows are a third as tall** — four labelled buttons wrapped into three lines below a wide screen; disable and delete moved into the edit dialog and the device type is now its icon
- **A traffic tile on the dashboard** — bytes in and out over the last hour
- **The topology diagram survives a crowded network** — the expanded resource list draws on its own panel instead of letting nodes show through, and the view moves to what you just opened
- **The update check notices a stale `update.sh`** — the copy kept on the hub can be older than the release it is meant to install
- Fixed: an admin viewing another person's access could add a device there and receive it themselves
- Fixed: a WireGuard subnet too narrow to ever assign a host was accepted
- Fixed: warning banners across seven screens named a stylesheet class that does not exist, so none of them were ever amber
- **Groundwork for edge hubs** — modelled, configurable and drawn, but not usable until there is software at the edge that fetches its configuration and enforces the firewall there. [docs/features.md](docs/features.md) says exactly where the line is

### Roadmap

Planned features are tracked as GitHub issues — 👍 or comment to signal what matters to you.

**v2 — Usability & convenience** ([milestone](https://github.com/chriscohnen/islandr/milestone/1))
- [Entra ID user import](https://github.com/chriscohnen/islandr/issues/12) — browse org users and import selected; the Google Workspace half of this shipped in 0.9.1

**v3 — Operations** ([milestone](https://github.com/chriscohnen/islandr/milestone/2))
- [Prometheus `/metrics`](https://github.com/chriscohnen/islandr/issues/71) — so the hub reports into the monitoring you already run

## What 1.0 promises

Not that the software is finished — that **upgrading stops being an event**.
Concretely, semantic versioning covers these and only these, and breaking any
of them takes a major version:

| Covered | Not covered |
|---|---|
| The external API (`/api/external/v1`) — paths, shapes, meanings | The console API (`/api/v1`), which serves the bundled UI and changes with it |
| `ISLANDR_*` variables and `/etc/default/islandr` | The frontend modules and CSS — no build step, so no published surface |
| Database migrations: forward, automatic, never hand-run SQL | The schema itself. Migrations are the promise, their shape is not |
| `setup-hub.sh`, `install-proxy.sh`, `update.sh`, `backup.sh` — flags, variables, paths | Internal Java packages. This ships a binary, not a library |
| Database location, unit name, sudoers scope | The WireGuard interface config — that file is yours, Islandr never writes it |

Downgrades are covered by neither: migrations run forward only, so going back
means restoring the backup `update.sh` takes before it swaps anything.

The reasoning is in [ADR-0033](docs/adr/0033-what-1-0-promises.md). The upgrade
and rollback path is exercised by CI on every tag, not only described here.

## Upgrading

The console tells you when a release exists — Settings shows the version and a check button, and
the command to install it next to the result. The command is the same one either way:

```bash
sudo curl -fsSL -o /opt/islandr/update.sh \
  https://github.com/chriscohnen/islandr/releases/latest/download/update.sh
sudo bash /opt/islandr/update.sh
```

`update.sh` is not a thin wrapper around a download. It verifies the checksum, then copies the
running binary to `islandr.prev` and takes a hot `sqlite3 .backup` of the database before
swapping anything. It watches the new version for fifteen seconds — longer than the unit's
`RestartSec`, so a process that starts and immediately dies is not mistaken for a healthy one —
and **restores both if it does not stay up**. A failed update ends where it began.

The database backup is the part people skip and shouldn't: migrations run at startup and there are
no undo migrations, so a version that migrates and *then* fails leaves a schema the previous binary
refuses to validate. Putting back only the binary would not start either.

```bash
sudo bash /opt/islandr/update.sh --rollback   # undo the last update
sudo bash /opt/islandr/update.sh --pre        # include release candidates
sudo bash /opt/islandr/update.sh v1.0.0       # pin a version
```

Separately, `backup.sh` writes a rotated, compressed copy of the database on a schedule — see
[docs/install.md](docs/install.md#backups). The rollback above covers the last update; that covers
everything else.

**The console will not update the hub for you, and that is deliberate.** It would be restarting the
service it is served from, so a migration that failed would take away the very page meant to report
the outcome.

## Documentation

- [docs/features.md](docs/features.md) — the complete feature inventory, grouped by area
- [docs/install.md](docs/install.md) — Installation guide (native binary + systemd, Docker Compose)
- [docs/install/manual.md](docs/install/manual.md) — the same install written out step by step, for a host that is not a fresh Debian/Ubuntu VPS
- [docs/install/hardening.md](docs/install/hardening.md) — why the systemd unit and sudoers file look the way they do
- [docs/install/identity-microsoft365.md](docs/install/identity-microsoft365.md) — registering the Entra ID app, the permissions Islandr needs, and the setup errors that do not name their cause
- [docs/install/dns-clients.md](docs/install/dns-clients.md) — reaching the internal zone from a client, the split-tunnel DNS field that fails silently, and what to check when a name does not resolve
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
