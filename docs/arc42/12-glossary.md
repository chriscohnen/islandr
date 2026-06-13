# 12. Glossary

| Term | Definition |
|---|---|
| **ACL** | Access Control List. In Islandr, the set of nftables rules derived from the RBAC model. Never edited by hand. |
| **AllowedIPs** | WireGuard field in a peer config that tells the client OS which destination IPs to route through the tunnel. Also used by the WireGuard kernel to determine which source IPs are accepted from a peer. |
| **Assigned IP** | The IP address inside the WireGuard subnet assigned to a specific Peer. Validated to be within the configured subnet and unique across all peers. |
| **Audit Log** | Immutable append-only record of every mutating action in Islandr: actor, action, target entity, timestamp. No application-level delete path. |
| **DryRun adapter** | A WireGuard or nftables adapter implementation that executes the CLI command but does not apply changes (validates only). Used in testing. |
| **Firewall state** | The current status of the nftables ruleset managed by Islandr: last reload timestamp, last reload result, rule count. Visible on the Admin Console dashboard. |
| **Grant** | A `RoleResourceGrant`: binds a Role to a Resource, with either `allPorts=true` or a limited set of `ResourcePort`s. The atomic unit of access control. |
| **Hub VM** | The internet-exposed Linux server that runs Islandr, WireGuard (hub mode), and nftables. All WireGuard peers connect to this machine. |
| **nftables** | Linux kernel packet filter framework. Replaces iptables. Islandr manages the `inet islandr` table exclusively. |
| **Peer** | A WireGuard client endpoint registered in Islandr. Has a public key, assigned IP, enabled/disabled state, and belongs to exactly one User. |
| **PeerActivitySample** | A time-series data point for a Peer: sampled handshake timestamp, endpoint IP, rx/tx byte counters. Retained for a configurable window (default 30 days). |
| **Private key retention** | Instance-wide setting controlling whether the WireGuard peer private key is stored after creation. `never` (default): key exists only in the create-peer API response. `plaintext`: stored in DB for re-display. |
| **RBAC0** | NIST Core RBAC: users assigned to roles, permissions (grants) assigned to roles. No role hierarchy, no separation-of-duty constraints. Islandr implements RBAC0 in v1. |
| **Resource** | A named host or service inside a Site (e.g. "Terminal-01" at `10.20.0.5`). Has one or more `ResourcePort`s. The target of a `RoleResourceGrant`. |
| **ResourcePort** | A reachable port on a Resource: port number, transport (`tcp`/`udp`), protocol label (`RDP`, `SSH`, `SFTP`, `HTTP`, …), optional human label. The protocol label is a UI display hint, not a security control. |
| **Role** | A named job-function group (e.g. `Vertrieb`, `IT-Admin`). Users are members of roles; roles hold grants. The indirection that makes RBAC efficient at scale. |
| **Ruleset** | The complete set of nftables rules generated from all active peers, their users, their roles, and the role grants. Always a pure function of current DB state. |
| **Self-Service Portal** | The end-user-facing web UI. German, informal _Du_, plain language. Allows Lena to enroll devices and view her access list without contacting IT. |
| **Session** | An authenticated HTTP session in Islandr. Stored as an `HttpOnly` cookie. Contains user ID, name, and `isAdmin` flag. |
| **Site** | A named logical network reachable through the hub (e.g. "Headquarter LAN", CIDR `10.20.0.0/24`). Groups Resources for browsing. The site CIDR is used in `AllowedIPs` generation, not as an nftables rule target. |
| **STRIDE** | Microsoft threat-modeling framework: Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege. |
| **UCG** | UniFi Cloud Gateway. The site-edge router in the target network. Manages internal VLAN routing. Islandr does not connect to the UCG in v1. |
| **WireGuard** | A modern, audited VPN protocol and Linux kernel module. Hub-spoke topology in Islandr: one server (hub VM) + N clients (peers). |
| **wg0** | The WireGuard interface name on the hub VM. Configurable via `islandr.wg.interface`, defaults to `wg0`. |
