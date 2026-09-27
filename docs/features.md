# What Islandr does today

The complete feature inventory — for checking whether Islandr already covers a
specific need. The short version of what the product *is* lives in the
[README](../README.md); what changed per release is in
[CHANGELOG.md](../CHANGELOG.md).

**Authentication & identity**
- Local admin (ENV-bootstrapped) *and* per-user local passwords (PBKDF2) — no external IdP required
- OIDC: Microsoft 365 / Entra ID and Google, fully GUI-configurable at runtime without a restart; one provider active at a time
- Avatars: MS Graph photo → Google picture → Gravatar (opt-in) → deterministic initials
- **Security keys for the local recovery admin** — a "sign in with a security key" button next to the password on the login screen, registered and managed from Settings; issues the same session a password does, offered alongside it and never instead of it, several authenticators can be registered. A credential binds to a name, so a hub reached by IP cannot use them. If every authenticator is lost, `ISLANDR_WEBAUTHN_RESET=true` plus a restart clears them, audit-logged ([ADR-0028](adr/0028-webauthn-library-and-integration.md), [#67](https://github.com/chriscohnen/islandr/issues/67))

**Users, peers & devices**
- Users with roles, plus a default **Everyone** role every user belongs to
- Peers: client and site types, IPv4 with optional **IPv6 dual-stack**, IP suggestion, CIDR-overlap validation, per-peer MTU
- Server-side keypairs or admin-imported public keys; **private-key retention** in three modes — `never` (default), `plaintext`, `encrypted` (AES-256-GCM)
- QR code + `.conf` download as a one-time secret; **import existing peers** from a live `wg0`
- **Admin-triggered key rotation** — regenerate a peer's keypair in place for compromised-device response, instead of deleting and recreating the peer; explicit confirmation required, rotation timestamps tracked separately for key and PSK ([#46](https://github.com/chriscohnen/islandr/issues/46))
- **Peer-Scheduler** — a recurring weekly time window that auto-enables/disables a peer, plus a terminal `validUntil` expiry that disables it for good regardless of any open window — closes the long-requested "contractor/trial device shouldn't need an admin to remember to remove it" ([#47](https://github.com/chriscohnen/islandr/issues/47), closes [#10](https://github.com/chriscohnen/islandr/issues/10))
- **Tri-state connection status** — Connected / Stale / Disconnected badges with absolute time thresholds, instead of a binary online/offline read of the last handshake
- Approximate peer location from the endpoint IP; hub location editable in Settings
- A user can rename their own device, change its category, and remove it from **My access** without an admin — removal disables it immediately and hides the row, but only an admin's real delete removes it, so nothing is lost if that turns out to be the wrong call
- Sortable name, email and peer-count columns on the users list, on top of the existing quick search
- The connection heatmap shows whose peer it is — an owner's avatar in front of the name, since names are per-user and free-form and two people can both call a device "Laptop"

**Networks, resources & firewall**
- Sites and typed resources (computer, router, printer, NAS, camera, IoT, rack server, KVM host, access point, …) — a new type is a regex-and-GUI change, not a migration
- **Device discovery** — scan a site's own CIDR for live hosts, identify them by their open ports, and bulk-create resources from a reviewable list. Discovery also suggests a name from whatever the host is willing to tell it. How much that is varies a lot by device and by network: some answer with a proper hostname, some with a product label, plenty answer nothing at all and keep the typed baseline. It is a head start on filling in a scan result, not an inventory system. An open port is named from a bundled IANA-derived table rather than shown as a bare number — from the classpath, never over the network. Unprivileged sockets only, no new capabilities ([ADR-0014](adr/0014-device-discovery.md), [#45](https://github.com/chriscohnen/islandr/issues/45), [#48](https://github.com/chriscohnen/islandr/issues/48))
- **Port-range scan for a single resource** — the same unprivileged TCP scan as discovery, but for a resource that's already known: type a range (or leave it blank for the well-known ports plus everything the service table can name) and add a hit as a port with one click. TCP only, stated as such
- **MAC address and hardware vendor, where the device gives one up** — a resource can carry its MAC, and the vendor ("Ubiquiti Networks", "Raspberry Pi Foundation") is named from a table bundled with the binary, so no lookup leaves the host. Same caveat as the name suggestion: it works for some devices and not others, and less often the further the device sits from the hub. An **Identify** action retries it on demand for a resource that has none ([#76](https://github.com/chriscohnen/islandr/issues/76))
- Resource-level ACL: roles → resource grants, per port, port ranges, or all ports
- **Resource-type ACL grants** — roles → every resource of a type at a site (e.g. "all printers in the home office"), additive to individual grants ([ADR-0022](adr/0022-acl-type-grants.md))
- **Microsoft 365 login is documented end to end** — registering the Entra ID app, the exact permissions Islandr needs and why, and the setup errors that never name their own cause ([docs/install/identity-microsoft365.md](install/identity-microsoft365.md)). The Identity page helps rather than assuming: the redirect URI is copyable with one click (with a fallback for a hub still reached over plain HTTP, which is exactly when an admin is configuring this), and the tenant field is labelled the way Entra labels it instead of the way the protocol does
- **Whole-network grants** — a role can be granted a whole site network at once, covering hosts added later. Deliberately coarse: always full access, no port scoping, and it reaches hosts Islandr has never been told about — use it where the network boundary already is the access boundary ([ADR-0029](adr/0029-whole-network-role-grants.md), [#78](https://github.com/chriscohnen/islandr/issues/78))
- **Direct user→resource grants** — grant one specific user access to a resource without a role, for one-off exceptions that don't warrant a new role ([ADR-0024](adr/0024-direct-user-resource-grants.md))
- **Site-to-site grants** — a site's gateway peer can itself be a grant subject, authorizing the whole site's CIDR (not just individual peers) to reach a resource, full-access or port-scoped ([#52](https://github.com/chriscohnen/islandr/issues/52))
- **Atlas view** — a global map of who/what can reach which resources across the whole tenant. Click a user, resource or site to narrow it down, drag to grant, revoke from the graph itself ([#49](https://github.com/chriscohnen/islandr/issues/49))
- **Network diagnostics from Atlas** — admin-triggered ping, tracepath, and mtr against a resource, a site's gateway peer, or any currently-connected client peer, run hub-side over an unprivileged shell (no `sudo`, no new capabilities). Results dock in a panel beside the graph and overlay the actual probed path (hub → site gateway → target) with live reachability/latency on the diagram itself ([ADR-0025](adr/0025-network-diagnostic-helpers.md), [#66](https://github.com/chriscohnen/islandr/issues/66))
- **World-map topology view** — sites, gateways and live tunnels on a geocoded map, alongside the existing network diagram ([#11](https://github.com/chriscohnen/islandr/issues/11), [ADR-0021](adr/0021-topology-world-map.md))
- **DNS resolver for resource names** — opt-in, hand-rolled UDP/TCP resolver authoritative for the managed resource zone (per-site subdomains), ACL-filtered per querying peer, everything else forwarded upstream unparsed ([ADR-0023](adr/0023-resource-dns-resolver-hand-rolled.md))
- **Dashboard traffic-tier topology** — network/topology links colour by actual traffic volume, not just handshake recency
- nftables ruleset generation with atomic, cold-start-safe reload
- **Docker without `NET_ADMIN`** — unprivileged container plus a host-side socket proxy ([ADR-0012](adr/0012-docker-socket-proxy.md))
- Enforcement state is always visible — direct, via proxy, or degraded. Nothing is ever silently unenforced
- Activity poller and live handshake indicators (last seen, endpoint, rx/tx)

**Self-service portal**
- Users enrol their own devices: platform → QR + `.conf` → first handshake. Key rotation, device list, access overview. Admins can switch it off
- **Own topology, geo-map, and activity heatmap** — the same visualisations the admin dashboard has, scoped to what the logged-in user can actually see; the heatmap uses a GitHub-contributions layout (weekday × week) instead of the admin's peers × days table ([#43](https://github.com/chriscohnen/islandr/issues/43))
- **Quicklaunch** on granted resources: HTTP/HTTPS (with optional path prefix), RDP, VNC, SSH, SFTP, SMB, and IPP printer install via native URI handlers
- **Browser-based RDP** (IronRDP WASM) — no client to install, ACL-gated, with per-port clipboard and file-transfer toggles and an optional `web-only` mode
- Platform-detected WireGuard client setup guide on first visit

**Operations**
- **Built-in TLS termination** — starts on a placeholder certificate, hot-swaps to your uploaded one at runtime, no reverse proxy required ([ADR-0015](adr/0015-builtin-tls-termination.md))
- **Automatic Let's Encrypt certificates** — set a domain and islandr requests, installs, and renews the certificate itself via a hand-rolled ACME client ([ADR-0019](adr/0019-acme-hand-rolled-client.md))
- **DNS-01 challenge** as an alternative to HTTP-01, including a manual no-API-token mode for registrars without a supported DNS API ([ADR-0020](adr/0020-dns01-challenge-with-manual-mode.md), [#41](https://github.com/chriscohnen/islandr/issues/41))
- **CSR generation for the Origin Certificate** — generate a private key + certificate signing request in-app instead of shelling out to `openssl` ([#42](https://github.com/chriscohnen/islandr/issues/42))
- **Connection activity heatmap** — peers × days, coloured by traffic volume rather than plain presence, so a device gone quiet stands out at a glance and a hover shows connection duration or ↓/↑ MB. Site gateways are set apart from client devices, since a quiet day means something different for each
- Google Workspace user import (the service-account JSON is encrypted at rest)
- Audit log with cursor pagination and actor/action/target filters
- Config **export/import** as a JSON snapshot, with preview and confirm
- **External API for automation** — a separate, versioned `/api/external/v1` surface, authenticated by admin-issued API keys (one-time reveal, hashed at rest, instantly revocable) instead of a session cookie; a hand-written OpenAPI spec (`GET /api/openapi.yml`) documents it. Read access to peers, users, sites, resources, roles, effective grants and the audit log; a user or a single peer can be disabled. A port-limited grant carries its ports as values — transport included — not only as display labels, so it can be turned into a rule. Growing incrementally ([ADR-0026](adr/0026-external-api-facade.md), [#15](https://github.com/chriscohnen/islandr/issues/15))
- On-demand update check — no telemetry, no background polling. Settings names the update command with a copy button, the rollback beside it, and whether a backup to roll back to actually exists; `update.sh` and `backup.sh` ship as release assets
- **Upgrades are a covered promise, not a hope** — `update.sh` backs up binary and database, verifies the checksum, watches the service come back and restores both if it does not. CI exercises that path, and the rollback, on every tag ([ADR-0033](adr/0033-what-1-0-promises.md))
- Bilingual UI, German default and English, switchable at runtime
