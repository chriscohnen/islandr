# Changelog

Only the changes that matter if you actually use Islandr — written for operators, not
as a commit dump. Binaries, checksums and the raw list of every change are on the
[GitHub releases](https://github.com/chriscohnen/islandr/releases) page.

The current release is also summarised in the [README](README.md#release-notes).

---

## 0.11.0

- **Docker without `NET_ADMIN`** — run the hub as an unprivileged container. A small host-side proxy (`islandr-proxy`) owns the WireGuard and nftables commands, and the container talks to it over a Unix socket, so it no longer needs broad host privileges. If the proxy is unreachable the hub stays up in a clearly-flagged degraded mode (peers and ACLs are managed, enforcement is paused) with a banner in the admin console instead of failing hard. The socket-proxy setup is documented in [docs/install.md](docs/install.md) ([ADR-0012](docs/adr/0012-docker-socket-proxy.md), [#13](https://github.com/chriscohnen/islandr/issues/13)).
- **Enforcement mode in Settings** — Settings now shows whether the hub is enforcing rules directly, through the socket proxy, or running degraded, so you can tell at a glance what is actually applying your ACLs.
- **A default "Everyone" role** — every user is automatically a member, so shared resources can be granted once to Everyone instead of per user or per group. Auto-managed; you cannot accidentally remove someone from it ([ADR-0013](docs/adr/0013-default-everyone-role.md)).
- **More resource types** — rack server and KVM/virtualisation host join the resource catalogue (with fitting icons), and you can switch an existing resource to them. New resources also adopt their site's CIDR and sensible port defaults, so there is less to type.
- **Configurable WireGuard interface** — set `ISLANDR_WG_INTERFACE` to run on a non-default interface name instead of the built-in one.
- **Polish** — edit a local user's name *and* email; the config-import file picker and the "Everyone" role description now follow the selected UI language; the ACL page uses a master-detail site list so a large network count no longer forces horizontal scrolling.

## 0.10.0

- **Browser-based RDP** — open a granted RDP resource straight from the self-service portal, with no local client to install. An IronRDP WASM client runs in the browser and the hub proxies the connection over a WebSocket, gated by the resource ACL (the target is derived from the database, not the request). Per-port clipboard and file-transfer toggles; a `web-only` access mode blocks the direct WireGuard port so users are forced through the auditable browser proxy. Off by default — enable it globally in Settings.
- **Password-manager-friendly credentials** — the local login form and the browser-RDP credential dialog use proper form and field semantics (`name` + `autocomplete` + `type`), so KeePassXC, Bitwarden and the browser's own manager detect and autofill them. The RDP dialog adds a copyable per-resource URI (keep one vault entry per host) and a show/hide toggle on the password field.
- **Local users with passwords** — you no longer need an external IdP to hand out logins. An admin sets a per-user password (PBKDF2-hashed, never stored or returned in plaintext) and that user signs in with email + password, alongside or instead of Microsoft/Google OIDC.
- **Usable bootstrap admin** — a fresh install seeds an `admin@local` user and binds the `ISLANDR_ADMIN_PASSWORD` login to it, so you can immediately own a peer and assign yourself roles instead of logging in as a rights-less admin.
- **Onboarding polish** — the port form pre-fills the default port per protocol (RDP 3389, SSH 22, HTTPS 443, …); a peer can be created with no users yet as a site gateway (the client type is disabled with a hint to add a user first); networks hint when there is no gateway peer to pick; the ACL matrix lets you grant "all ports" on single-port resources too; and the dashboard shows your configured hub location name instead of a generic "Hub".
- **No more stale UI after an update** — the front end is revalidated on each load, so a new release shows up without a manual hard-refresh.

## 0.9.2 – 0.9.4

Fixes only, no new features: the Docker image and native binary now boot on a plain `docker run` and on CPU-restricted hosts (e.g. a Proxmox VM on the default CPU model). If you deploy with Docker, install **0.9.4 or later**.

## 0.9.1

- **Path prefix for HTTP/HTTPS resources** — a resource port can carry an optional URL path (e.g. `/admin`), so the portal's quicklaunch opens `https://host/admin` instead of just the host root.
- **Hub coordinates** — set the gateway's own location (latitude/longitude + label) in Settings, by entering coordinates or geocoding an address, so the hub shows up where it really is instead of being guessed from its IP.
- **Settings page restructured** into clearer sections.
- **Google Workspace user import** — browse your org's users and import the ones you pick; the service-account JSON is encrypted at rest.
- Update check moved into Settings with a corrected semver comparison.

## 0.9.0

- **Protocol quicklaunch in the self-service portal** — granted resources open directly: HTTP/HTTPS in the browser, plus RDP, VNC, SSH, SFTP, SMB and IPP printer install via native URI handlers.
- **WireGuard client setup guide** — platform-detected install links for new users on first visit.
- **IPv6 dual-stack peers** — optional IPv6 address per peer alongside IPv4.
- **Encrypted private-key retention** — optionally keep keys server-side under AES-256-GCM so a config can be re-shown later, or stay key-less (the default).
- **Config export/import** — full snapshot as JSON with a preview-and-confirm import.
- **Per-peer MTU override** and **reverse geocoding** of a peer's approximate location from its endpoint IP.
- **Update check** — Settings shows the running version and checks GitHub for a newer release on demand (no telemetry, no background polling).
