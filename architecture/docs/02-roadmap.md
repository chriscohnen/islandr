# Roadmap

Where Islandr is heading. The authoritative, live backlog is on GitHub — this
page is a snapshot for orientation. Each item links to its tracking issue; a
👍 or comment there is how you signal interest and shape priority.

**Backlog:** [github.com/chriscohnen/islandr/issues](https://github.com/chriscohnen/islandr/issues)

## Release lines

The version stream ships small, frequent releases. Thematic milestones group
the work:

- **v1 — Core** — everything required before a 1.0. Includes production Docker.
- **v2 — Usability & convenience** — quality-of-life features after the core.
- **v3 — Operations** — packaging and operating at scale.

## Shipped

- **0.12.1** — current stable release. Fully bilingual UI (EN/DE parity across
  every screen, relative times included).
- **0.12.0** — device discovery: unprivileged CIDR scan that lists live hosts
  on a site and turns them into resources in one go
  ([#20](https://github.com/chriscohnen/islandr/issues/20), EPIC). See ADR-0014.
- **0.11.0** — production Docker via a Unix socket proxy: a host-side
  `islandr-proxy` holds the `wg`/`nft` privilege so the container needs no
  capabilities, no host PID namespace, no Docker socket
  ([#13](https://github.com/chriscohnen/islandr/issues/13)). See ADR-0012.
  Also: a default "Everyone" role with auto-membership
  ([#21](https://github.com/chriscohnen/islandr/issues/21)). See ADR-0013.
- **0.10.0** — peer lifecycle, group-based ACL matrix, self-service portal,
  OIDC login, audit log, nftables enforcement, browser-based RDP.

## Next

- **HTTPS without a reverse proxy**
  ([#22](https://github.com/chriscohnen/islandr/issues/22), should) — built-in
  TLS termination plus a Cloudflare edge, so a reverse proxy becomes optional
  rather than required.

## Under consideration

Not yet scheduled — priority is driven by interest on the tracking issues.

- **Dashboard topology** ([#24](https://github.com/chriscohnen/islandr/issues/24), EPIC) — group networks under their shared gateway peer.
- **Peer expiry / auto-disable** ([#10](https://github.com/chriscohnen/islandr/issues/10)).
- **Multi-site map view** (Leaflet + OSM, [#11](https://github.com/chriscohnen/islandr/issues/11)).
- **Google Workspace / Entra ID user import** ([#12](https://github.com/chriscohnen/islandr/issues/12)).
- **`.deb` package for `apt install`** ([#14](https://github.com/chriscohnen/islandr/issues/14)).
- **API key management for automation** ([#15](https://github.com/chriscohnen/islandr/issues/15)).
- **TLS/HTTPS setup guide** ([#23](https://github.com/chriscohnen/islandr/issues/23), docs).

---

*Snapshot generated from the GitHub backlog. Dates and scope are indicative,
not commitments.*
