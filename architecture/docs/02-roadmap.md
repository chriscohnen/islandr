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

- **0.13.0** — current stable release. **HTTPS without a reverse proxy**:
  built-in TLS termination, starting on a placeholder certificate and
  hot-swapping to an uploaded one at runtime, no restart
  ([#22](https://github.com/chriscohnen/islandr/issues/22)). See ADR-0015.
  Also: the dashboard topology's gateway-grouping polished off — busy sites
  fan out and pan instead of overlapping, offline gateways get a dashed
  link, the resource-overflow count is finally shown
  ([#24](https://github.com/chriscohnen/islandr/issues/24), EPIC); concrete
  MTU value presets and guidance copy wherever MTU is set, including an
  automatic choice in the self-service portal
  ([#31](https://github.com/chriscohnen/islandr/issues/31)). Also: installed-certificate
  detail (domain/SAN/validity/issuer) in Settings, clearer TLS validation error
  messages, connected-peer name+IP labels on the topology diagram, and installers
  that generate a working encryption key out of the box.
- **0.12.1** — fully bilingual UI (EN/DE parity across every screen, relative
  times included).
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

Targeted for 0.14.0:

- **Connection activity heatmap** ([#32](https://github.com/chriscohnen/islandr/issues/32)) —
  peers × days, daily-aggregated so it stays cheap to store and query. See ADR-0016.
- **Full/split tunnel as an explicit setting**
  ([#33](https://github.com/chriscohnen/islandr/issues/33)) — the split-tunnel
  network list is independent of a peer's current ACL grants, so changing a
  grant never forces a re-import of the `.conf`. See ADR-0017.

## Under consideration

Not yet scheduled — priority is driven by interest on the tracking issues.

- **Native integration test for discovery endpoints**
  ([#25](https://github.com/chriscohnen/islandr/issues/25)) — catch
  native-image serialization regressions in CI, not in a user's deployment.
- **Peer expiry / auto-disable** ([#10](https://github.com/chriscohnen/islandr/issues/10)).
- **Multi-site map view** (Leaflet + OSM, [#11](https://github.com/chriscohnen/islandr/issues/11)).
- **Google Workspace / Entra ID user import** ([#12](https://github.com/chriscohnen/islandr/issues/12)).
- **`.deb` package for `apt install`** ([#14](https://github.com/chriscohnen/islandr/issues/14)).
- **API key management for automation** ([#15](https://github.com/chriscohnen/islandr/issues/15)).
- **TLS/HTTPS setup guide** ([#23](https://github.com/chriscohnen/islandr/issues/23), docs).
- **ACME (Let's Encrypt) auto-provisioning** ([#30](https://github.com/chriscohnen/islandr/issues/30)) — follow-up to the built-in TLS termination shipped in 0.13.0.
- **Topology diagram: live traffic activity, not just handshake recency** ([#34](https://github.com/chriscohnen/islandr/issues/34)).

---

*Snapshot generated from the GitHub backlog. Dates and scope are indicative,
not commitments.*
