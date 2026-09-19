# Changelog

One line per change, newest first. Binaries, checksums and the raw commit list are on the
[GitHub releases](https://github.com/chriscohnen/islandr/releases) page; the current release is
summarised in the [README](README.md).

Where a change has a rationale worth reading before you rely on it, the linked ADR carries it.

---

## 0.23.0

- **Fixed: a peer stayed "Connected" after it had gone.** The status badge and the "last handshake" column showed the time of the poll rather than the handshake, so Stale and Disconnected were unreachable for any peer that had ever connected ([#87](https://github.com/chriscohnen/islandr/issues/87)).
- **Fixed: the activity heatmap could not report a site outage** — a gateway unreachable for days kept filling its row, so the one case the outage marker exists for never fired.
- **Security keys for the local recovery admin** — several authenticators can be registered, and signing in with one issues the same session a password does. Requires the console to be reached by name; an IP address cannot carry a credential ([ADR-0028](docs/adr/0028-webauthn-library-and-integration.md), [#67](https://github.com/chriscohnen/islandr/issues/67)).
- **The resolver answers for the hub itself** — `hub.<zone>` plus an optional alias, so the console is reachable by name without an `/etc/hosts` entry on every device.
- **A self-signed certificate for those names**, for installations with no domain — no public CA can issue for them at all. Settings shows its SHA-256 fingerprint to compare once, rather than leaving a warning to be clicked away.
- **External API: a user or a single peer can be disabled.** Disabling a user takes their peers down with the account.
- **External API: effective grants and the audit log are readable** — who can reach what, and who changed it. Read-only; the audit log's purge has no counterpart there.
- **Fixed: resource cards cut the name short, and their buttons needed a mouse.** Deleting moved into the edit dialog, the full name is available on hover, and the actions no longer appear only on hover — a tablet can use them now.
- **Admins can reach their own self-service portal** — the link existed only for non-admin accounts, though the page always worked.

---

## 0.22.0

- **Fail-closed boot firewall** — a hub whose Islandr does not start now forwards nothing, instead of forwarding the peers in `<iface>.conf` unfiltered. Existing installs need `setup-hub.sh` re-run ([ADR-0031](docs/adr/0031-fail-closed-boot-ruleset.md), [#84](https://github.com/chriscohnen/islandr/issues/84)).
- **Failed local logins carry a progressive delay** — counted per account *and* per source address, never a lockout ([#80](https://github.com/chriscohnen/islandr/issues/80)).
- **A failed login writes a line fail2ban can match**, with the client address and how long the attempt was held; filter and jail in [docs/install/fail2ban.md](docs/install/fail2ban.md).
- **Trusted proxies are a setting** — `X-Forwarded-For` counts only for proxies named under Settings → Reverse proxy, empty by default; `setup-hub.sh` seeds it with `TRUSTED_PROXIES=`.
- **Resource ports can be edited** — a port's thirteen fields were create-only, so correcting one meant deleting the port and every grant on it ([#82](https://github.com/chriscohnen/islandr/issues/82)).
- **A network's DNS server is settable in the scan dialog** and once per gateway on import — without it, a routed network imports as a column of `computer-42` ([#74](https://github.com/chriscohnen/islandr/issues/74)).
- **Avatars can be uploaded** — admins set anyone's, users their own; until now a face arrived only through OIDC or Gravatar ([#85](https://github.com/chriscohnen/islandr/issues/85)).
- **Entra ID setup can be tested** — tenant, client ID, secret and redirect URI are checked separately and the failing one is named; a Secret ID pasted into the Value field is refused before anything is saved.
- Fixed: browser-RDP never saw the automatic "Everyone" role and refused sessions the ruleset allowed ([#83](https://github.com/chriscohnen/islandr/issues/83)); the Microsoft admin-consent return reported a CSRF failure for a consent that had succeeded ([#81](https://github.com/chriscohnen/islandr/issues/81)); the downloaded `.rdp` ignored the port's clipboard setting; IPv6-only peers were offered for an import that could not succeed.

---

## 0.21.0

- **Whole-network grants** — a role can be given a network rather than every host in it. Always full access, no port scoping, and it reaches hosts Islandr has never been told about ([ADR-0029](docs/adr/0029-whole-network-role-grants.md), [#78](https://github.com/chriscohnen/islandr/issues/78)).
- **MAC address and hardware vendor on resources** — resolved from a table inside the binary, so nothing is looked up over the network. Help with naming a scan, not an inventory ([#76](https://github.com/chriscohnen/islandr/issues/76)).
- **Discovery shows hosts while the scan runs**, names which lookup sources it can use for this network, and keeps what it found when stopped ([#75](https://github.com/chriscohnen/islandr/issues/75), [#79](https://github.com/chriscohnen/islandr/issues/79)).
- **Export peers to `<iface>.conf`** — the mirror of "Import from wg0", so removing Islandr does not take its peers with it.
- **Unmanaged peers are on the Dashboard** — peers on the interface Islandr does not manage were visible only to someone who opened the import dialog.
- **Atlas is navigable** — sites selectable, grant kinds readable as text rather than only as an edge colour, grants revocable from the graph.
- **The service starts before the tunnel**, closing the boot-time forwarding gap on a hub where Islandr does come up; the case where it does not is [#84](https://github.com/chriscohnen/islandr/issues/84).
- **WebAuthn: decided, not built** — [ADR-0028](docs/adr/0028-webauthn-library-and-integration.md) settles the approach; no implementation in this release.
- Fixed: a reboot emptied the tunnel — Islandr re-applies its peers at startup and repairs drift while running ([ADR-0030](docs/adr/0030-wireguard-config-file-ownership.md)); an adopted hub could hand out an address a config-file peer already held; a whole-network grant would have reopened capacity-limited ports ([#72](https://github.com/chriscohnen/islandr/issues/72)); the heatmap called healthy gateways outages ([#77](https://github.com/chriscohnen/islandr/issues/77)).

---

## 0.20.0

- **Exclusive ports** — a port can declare how many people may hold it at once: a grant decides who may ask, a reservation who holds it right now ([#72](https://github.com/chriscohnen/islandr/issues/72)).
- **User-level access expiry** — a deadline on the user, checked at login and on every request, cascading to their peers. Closes the bypass where a contractor simply enrolled a fresh device ([#53](https://github.com/chriscohnen/islandr/issues/53)).
- **Security fix: the OIDC login path never checked whether an account was enabled** — a disabled user could sign in through the identity provider.
- **Security fix: sessions outlived the access they were issued for** — disabling a user took effect only at their next login. Every authenticated request re-checks now.
- **Hub load on the Dashboard** — CPU, memory and swap read from `/proc`, preferring a container's cgroup limit over host totals ([#73](https://github.com/chriscohnen/islandr/issues/73)).
- **Discovery names more devices** — mDNS asks the host directly, SSDP/UPnP joins the chain, and link-scope protocols are skipped where they cannot answer.
- **Adopting an existing WireGuard hub got a lot less manual** — bulk select on import, gateways recognised and pre-filled with their networks, a peer's type and owner editable afterwards.
- **`update.sh` can undo a failed update** — binary *and* database backed up before the swap, with `--rollback` on demand.
- **`setup-hub.sh` checks what actually breaks a fresh install** — WireGuard up, enough memory, free ports, firewall rules — and downloads and checksums the binary itself.
- Fixed: the Admin Console called the interface `wg0` whatever it was called; every inline Vue template is now compiled in CI ([ADR-0002](docs/adr/0002-vue-without-npm.md)).

---

## 0.19.0

- **Generic OIDC provider support** — Auth0, Okta or any compliant issuer alongside Microsoft 365 and Google Workspace, discovered via `.well-known` ([#69](https://github.com/chriscohnen/islandr/issues/69)).
- **Outgoing webhooks** — peer, ACL, discovery and certificate events, filtered per webhook, HMAC-SHA256-signed or in Gotify's native format ([#68](https://github.com/chriscohnen/islandr/issues/68)).
- **External API for automation** — a separate `/api/external/v1` surface with API-key auth and a hand-written OpenAPI spec ([ADR-0026](docs/adr/0026-external-api-facade.md), [#15](https://github.com/chriscohnen/islandr/issues/15)).
- **Ad-hoc temporary access grants** — a direct user→resource grant can carry an expiry and is auto-revoked on schedule ([#70](https://github.com/chriscohnen/islandr/issues/70)).
- **Roles & ACL split into a role matrix and direct grants**, with a resource filter for large sites.
- **Security fix: disabling a user now disables their peers too** — before, only the login was blocked and configured tunnels kept working.

---

## 0.18.0

- **Network diagnostics from Atlas** — admin-triggered ping, tracepath and mtr, run unprivileged, with the probed path overlaid on the graph ([ADR-0025](docs/adr/0025-network-diagnostic-helpers.md), [#66](https://github.com/chriscohnen/islandr/issues/66)).
- **Discovery falls back to mDNS and NetBIOS** for hosts a router's DNS never learned a name for ([#48](https://github.com/chriscohnen/islandr/issues/48)).

---

## 0.17.1

- **Security fix: local-password sessions were treated as the bootstrap admin** regardless of the user's actual admin flag ([#55](https://github.com/chriscohnen/islandr/issues/55)).
- Fixed: the DNS resolver ignored direct user→resource grants ([#54](https://github.com/chriscohnen/islandr/issues/54)); the add-grant dialog offered a "no access" option that did nothing ([#57](https://github.com/chriscohnen/islandr/issues/57)); admin key rotation threw a 500 ([#56](https://github.com/chriscohnen/islandr/issues/56)); several layout defects found in live use ([#58](https://github.com/chriscohnen/islandr/issues/58), [#63](https://github.com/chriscohnen/islandr/issues/63)).

---

## 0.17.0

- **Peer-Scheduler** — recurring weekly windows that auto-enable/disable a peer, plus a terminal expiry ([#47](https://github.com/chriscohnen/islandr/issues/47), closes [#10](https://github.com/chriscohnen/islandr/issues/10)).
- **Site-to-site grants** — a site's gateway peer can itself be granted a resource, authorizing the whole CIDR ([#52](https://github.com/chriscohnen/islandr/issues/52)).
- **Atlas view** — a global graph of who can reach what, with click-to-focus and drag-to-grant ([#49](https://github.com/chriscohnen/islandr/issues/49)).
- **Direct user→resource grants** — a one-off exception without going through roles ([ADR-0024](docs/adr/0024-direct-user-resource-grants.md), [#50](https://github.com/chriscohnen/islandr/issues/50)).
- **Keyboard shortcuts** — Escape, `/` and Ctrl/Cmd+S ([#51](https://github.com/chriscohnen/islandr/issues/51)).

---

## 0.16.0

- **Admin-triggered key rotation** — regenerate a peer's keypair in place instead of deleting and recreating it ([#46](https://github.com/chriscohnen/islandr/issues/46)).
- **DNS resolver for resource names** — opt-in, authoritative for the managed zone, ACL-filtered answers ([ADR-0023](docs/adr/0023-resource-dns-resolver-hand-rolled.md)).
- **Tri-state peer connection status** — Connected / Stale / Disconnected, replacing the binary read.
- **The self-service portal gets its own topology, geo-map and heatmap**, scoped to the logged-in user ([#43](https://github.com/chriscohnen/islandr/issues/43)).
- **Reverse-proxy vs. built-in TLS install guide** ([docs/install/reverse-proxy.md](docs/install/reverse-proxy.md)), plus an SQLite backup script.

---

## 0.15.1

- Fixed: config export/import dropped the hub's map location ([#44](https://github.com/chriscohnen/islandr/issues/44)).

---

## 0.15.0

- **Grant access by resource type**, not just by individual resource ([ADR-0022](docs/adr/0022-acl-type-grants.md)).
- **World-map topology view** ([ADR-0021](docs/adr/0021-topology-world-map.md), [#11](https://github.com/chriscohnen/islandr/issues/11)).
- **DNS-01 as an alternative to HTTP-01** — automated against Cloudflare, manual for anyone else ([ADR-0020](docs/adr/0020-dns01-challenge-with-manual-mode.md), [#41](https://github.com/chriscohnen/islandr/issues/41)).
- **CSR generation for the Origin Certificate tab** ([#42](https://github.com/chriscohnen/islandr/issues/42)); the activity heatmap is coloured by traffic volume; a stuck ACME attempt can be cancelled.

---

## 0.14.0

- **Let's Encrypt, fully automatic** — request, install and renew without an external ACME client ([ADR-0019](docs/adr/0019-acme-hand-rolled-client.md), [#30](https://github.com/chriscohnen/islandr/issues/30)).
- **Connection activity heatmap** ([#32](https://github.com/chriscohnen/islandr/issues/32)); device discovery scans by default; the resource list sorts and shows a real "last modified".
- **wg0 bootstrap commands are shown, not run** ([#40](https://github.com/chriscohnen/islandr/issues/40)), and the enforcement banner says what actually broke ([#37](https://github.com/chriscohnen/islandr/issues/37)).
- Fixed: site peers with more than one CIDR did not push ([#38](https://github.com/chriscohnen/islandr/issues/38)); removing a preshared key did not remove it ([#39](https://github.com/chriscohnen/islandr/issues/39)); the forward chain blocked unrelated Docker traffic on a shared host ([#36](https://github.com/chriscohnen/islandr/issues/36)).

---

## 0.13.0

- **HTTPS without a reverse proxy** — Islandr terminates TLS itself ([ADR-0015](docs/adr/0015-builtin-tls-termination.md), [#22](https://github.com/chriscohnen/islandr/issues/22)).
- **MTU guidance instead of a bare number field**, with the portal picking a value for new peers ([#31](https://github.com/chriscohnen/islandr/issues/31)).
- **MTU, keepalive and DNS are editable** without recreating the peer; discovery can scan past a stale handshake; new installs get a working encryption key out of the box.

---

## 0.12.1

- **The UI is fully bilingual** (German default, English), error messages no longer show a raw translation key, and the seeded port groups are English.

---

## 0.12.0

- **Device discovery** — scan a network and turn what answers into resources ([ADR-0014](docs/adr/0014-device-discovery.md), [#20](https://github.com/chriscohnen/islandr/issues/20)).
- **Resource list with bulk actions**; Docker no longer pretends to enforce ([ADR-0012](docs/adr/0012-docker-socket-proxy.md)); config import no longer destroys the instance.

---

## 0.11.0

- **Docker without `NET_ADMIN`** ([ADR-0012](docs/adr/0012-docker-socket-proxy.md), [#13](https://github.com/chriscohnen/islandr/issues/13)).
- **Enforcement mode in Settings**, a default **Everyone** role ([ADR-0013](docs/adr/0013-default-everyone-role.md)), more resource types, and a configurable WireGuard interface.

---

## 0.10.0

- **Browser-based RDP**, **local users with passwords**, a usable bootstrap admin, and password-manager-friendly credential fields.

---

## 0.9.2 – 0.9.4

- Fixes only: the Docker image and native binary boot on a plain `docker run` and on CPU-restricted hosts. Deploy with Docker? Use **0.9.4 or later**.

---

## 0.9.1

- **Path prefix for HTTP/HTTPS resources**, **hub coordinates** in Settings, **Google Workspace user import**, and an on-demand update check.

---

## 0.9.0

- **Protocol quicklaunch in the self-service portal** — RDP, VNC, SSH, SFTP, SMB and IPP via native URI handlers.
- **IPv6 dual-stack peers**, **encrypted private-key retention**, **config export/import**, per-peer MTU override, and a WireGuard client setup guide.
