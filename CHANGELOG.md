# Changelog

Only the changes that matter if you actually use Islandr — written for operators, not
as a commit dump. Binaries, checksums and the raw list of every change are on the
[GitHub releases](https://github.com/chriscohnen/islandr/releases) page.

The current release is also summarised in the [README](README.md#release-notes).

---

## 0.13.0

- **HTTPS without a reverse proxy** — islandr can terminate TLS itself now, so a Caddy/nginx/Traefik in front of it is optional rather than required. It boots on a placeholder self-signed certificate (so HTTPS is reachable from the very first start) and swaps in your real certificate the moment you upload it in Settings — no restart, no dropped connections, hot-reloaded via the Quarkus TLS registry. The plain HTTP port keeps working alongside it ([ADR-0015](docs/adr/0015-builtin-tls-termination.md), [#22](https://github.com/chriscohnen/islandr/issues/22)).
- **MTU guidance, not just a number field** — the MTU override, wherever it's set (the peer edit modal, the .conf/QR reveal dialog, and the global default in Settings), now shows three concrete presets as one-click chips — **1420** (default, stable fibre/cable/LAN), **1392** (European DSL/PPPoE), **1280** (mobile/roaming compatibility floor) — with copy explaining which value fixes which symptom, instead of a bare `<input type=number>` and a generic "only set on fragmentation issues" hint ([#31](https://github.com/chriscohnen/islandr/issues/31)).
- **The self-service portal picks MTU for you** — end users adding a device via the portal now choose a device category, "Stationary (PC, server)" or "On the go (phone, laptop)", instead of ever seeing the word MTU. "On the go" applies the 1280 compatibility floor automatically to the generated `.conf`; stationary gets no override.
- **Edit MTU, keepalive, and DNS without recreating the peer** — the .conf/QR reveal dialog gained its own MTU/PersistentKeepalive/DNS-inclusion controls, so an admin handing over a QR code can adjust these on the spot and re-render the config, instead of closing the dialog, opening the edit modal, saving, and reopening the reveal dialog.
- **Discovery can scan past a stale handshake** — a real-mode scan used to hard-block with "the site's gateway peer is not connected (no recent handshake)" and no way around it. A "scan anyway" option now lets an operator pre-configure resources on a site ahead of rolling out its gateway peer, accepting that the scan may be incomplete.
- **Topology diagram: gateway-grouping polish** ([#24](https://github.com/chriscohnen/islandr/issues/24), EPIC) — a network whose resources didn't make the diagram-wide display cap used to show an undercounted total, or nothing at all once drilled into; both are fixed by fetching that site's real, uncapped resource list on demand instead of relying on the capped payload. Busy sites (many resources, or many networks under one gateway) now fan out across a wider arc, and past a size limit the diagram pans by drag instead of shrinking nodes and labels into unreadable specks. Offline gateways get a dashed hub-to-gateway link, not just a grey ring. The resource-overflow count — already computed server-side but never shown — now renders as a note when the global cap truncates the payload.
- **ACL matrix: pre-grant "all ports" before any port exists** — a resource with zero ports defined used to have its matrix cell disabled outright, blocking a legitimate case: pre-granting "all ports (incl. future)" to a role before the first port is even added. Only the "limited to specific ports" option still needs concrete ports to choose from.

## 0.12.1

- **The UI is now fully bilingual.** The language toggle already existed, but several screens still had German baked into the view code — the peer and device dialogs, the browser-RDP connect flow and its error messages (including the TLS/socket failure labels), the topology view, the "setup incomplete" banner, and a number of form hints and placeholders — so switching the interface to English left German text behind. Relative times ("vor 3 h") and absolute dates were hardcoded to German too. Every user-visible string now resolves through the DE/EN catalogue, relative times and dates render in the active language, and the two language sets are kept at parity. Route URIs stay English in both languages, so links and bookmarks are stable across the toggle.
- **Error messages no longer show a raw key.** Saving a site, loading or saving settings, and applying a port group could fail with the literal text `sites.error_save` (and similar) shown to the user instead of a message, because those keys were referenced in the code but never defined in the catalogue. They now read as proper sentences in both languages.
- **The enforcement banner no longer overflows.** In degraded mode the long "from source" install command ran off the right edge of the banner (pushing its copy button out of view) instead of scrolling within its own line; it — and any wide content in a callout — now stays inside the box. The stylesheet is now revalidated on load like the rest of the app code (it was missing from the no-cache filter), so UI fixes reach you without a manual hard-refresh.
- **The sidebar stays put.** On long pages the navigation scrolled away with the content, so "Settings" at its foot was easy to miss; the rail is now pinned to the viewport and always reachable.
- **The seeded port groups are English.** The five default port groups shipped with German names and descriptions ("Drucker_Standard_Ports", "Windows-Dateifreigabe", …); a migration localizes them to English, touching only the rows still carrying the original seed values so renamed/edited groups are left alone.

## 0.12.0

- **Device discovery** — stop typing IP addresses. Point the hub at a site and it scans that site's own CIDR, lists the hosts that are actually live, guesses what each one is from its open ports, and hands you a checklist to create resources from in one go. (The trigger for this was an operator adding nine cameras by hand.) Names come from reverse DNS where available, hosts you already manage are marked as known, the suggested ports can be adopted per host, and a running scan shows live progress and can be aborted. The scan uses ordinary unprivileged sockets — no raw sockets, no new capabilities, no extra `sudoers` entry — and it stays clear of the enforcement path entirely ([ADR-0014](docs/adr/0014-device-discovery.md), [#20](https://github.com/chriscohnen/islandr/issues/20)).
- **Resource list with bulk actions** — resources now have a list view with multi-select and bulk delete, so cleaning up after a discovery run is one action instead of many.
- **Docker no longer pretends to enforce** — a plain `docker run` used to fall back to the in-memory mock adapter, which accepts peer and rule changes and even simulates online peers, so the console looked like it was enforcing while nothing reached the host kernel. The container now runs the real socket adapter and says plainly that enforcement is unavailable until the host proxy is attached. **If you evaluate Islandr in Docker, take this release** ([ADR-0012](docs/adr/0012-docker-socket-proxy.md)).
- **Config import no longer destroys the instance** — importing a config used to leave a database it could not recover from, through two separate faults. The timestamps were written in a format SQLite stored happily but could never read back, so the import reported success and every request touching an imported row then failed with HTTP 500 — peers, users, avatars. And the export silently dropped the auto-membership flag on the "Everyone" role, so the next start tried to create a second one, hit the unique index on the role name, and the application **failed to boot at all** — on that restart and every one after. Both are fixed, and both heal themselves on the next start: the timestamps are normalised in place and the existing "Everyone" role is adopted instead of duplicated. No re-install, no restore from backup. **If you have ever used config import, take this release.**
- **Copy buttons stay inside the enforcement banner** — the long install commands pushed them out of the box at some window widths and zoom levels; the command now scrolls within its own line instead.

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
