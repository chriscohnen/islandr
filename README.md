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
> **Pre-1.0 — in production use, but the upgrade path is not promised yet.**
> Islandr drives WireGuard and nftables directly (`wg set`, `ip link`, `nft`). Read the release notes before upgrading: until 1.0 a release can still ask for a manual step, and two recent ones did. `scripts/update.sh` backs up both the binary and the database first and rolls both back if the service does not come up — use it rather than replacing the binary by hand.
> This is exactly the stage where testers make the biggest difference. Kick the tyres, and if you hit a rough edge [open an issue](https://github.com/chriscohnen/islandr/issues) — that feedback is what moves it toward 1.0. Starring or watching the repo is the easiest way to follow releases.

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

**Pre-1.0 — the feature set is complete; the next release is about the upgrade path.**
1.0 is not a claim that the software is finished. It is one specific promise: that
upgrading stops asking for manual steps. Two of the last three releases needed one
(`systemctl edit`, re-running `setup-hub.sh`), and that is the gap being closed —
so the number arrives when it is earned, not on a date.

The full feature inventory — everything that works today, grouped by area —
lives in [docs/features.md](docs/features.md).

### What's new in 1.0.0

Every version: [CHANGELOG.md](CHANGELOG.md) · binaries and checksums:
[GitHub releases](https://github.com/chriscohnen/islandr/releases).

- **Upgrades are boring — and now proven so.** `update.sh` backs up the binary and the database, verifies the checksum, watches the service come back and restores both if it does not. Every tagged build installs the previous release, upgrades to it and rolls back again, asserting after each step ([ADR-0033](docs/adr/0033-what-1-0-promises.md))
- **Settings hands you the update instead of just announcing it** — the command with a copy button, the rollback beside it, and whether a rollback actually exists. `update.sh` and `backup.sh` ship as release assets
- **On the Quarkus LTS line** (3.33.3.2). The previous pin claimed LTS and was not, which is how a critical Netty advisory became unfixable without anyone noticing ([ADR-0032](docs/adr/0032-quarkus-lts-line.md))
- **External API: grants carry their ports as values** — transport included, so a port-limited grant can finally be turned into a rule
- **Security keys have a console** — a "sign in with a security key" button next to the password, registered and managed from Settings; the password stays a complete path on its own. An offline escape hatch too: `ISLANDR_WEBAUTHN_RESET=true` clears every registered authenticator on the recovery admin, audit-logged, for the case where you cannot sign in to remove one ([#67](https://github.com/chriscohnen/islandr/issues/67))
- **The console says it is open source** — EUPL-1.2 next to the version and in the login footer, with the trademark boundary named
- Fixed: Settings and My access were unreachable on a phone; discovery showed port numbers where it can show names; the avatar's edit controls sat permanently in the topbar

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
