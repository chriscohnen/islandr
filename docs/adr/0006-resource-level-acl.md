# ADR-0006 — Resource-level ACL with NIST RBAC0

**Status:** Accepted
**Date:** 2026-05-30
**Deciders:** Christian Cohnen
**Supersedes:** the original "Group × Network" ACL model in early drafts of [docs/prd.md](../prd.md).

## Context

The original PRD modelled access as `Group × Network`: a user joined groups, groups had access to whole CIDR networks, nftables produced one accept rule per `(peer.ip → network.cidr)` pair. This is the wg-easy / NetBird-lite shape — easy to understand, easy to implement, and how every "WireGuard VPN with groups" tutorial frames the problem.

It is also wrong for the use case operators actually care about.

When an admin sets up a VPN, what they describe in their head is not "group A can reach 10.20.0.0/16". It's "Vertrieb can RDP to the terminal server, IT can SSH to the switches, nobody outside IT touches the NAS." Two failures of the old model:

1. **Site CIDR is too coarse.** Granting `10.20.0.0/16` to a role means *every host in the LAN*, including the switch, the NAS, the printer, the dev box someone left running. That's not least-privilege; it's least-effort.
2. **The end-user portal can't say anything useful.** "Du hast Zugriff auf 10.20.0.0/16" is jargon. "Du kannst per RDP auf Terminal-01" is what the user actually wants to read.

Permission directly on users (no role layer) was also discussed and rejected early: 100 users × 50 resources × 2 actions = 10 000 explicit permissions to audit. Per the standard RBAC argument (NIST INCITS 359-2004, AWS IAM, Kubernetes RBAC all converge on this), insert a `Role` indirection and the same set becomes 100 user-role links + ~5 roles × 50 resources = ~350 facts.

Two questions blur together in the requirement "users want RDP/SSH/SFTP/X11 access to resources":

- **Q1: Per-resource granularity.** Filter on `(peer.ip → resource.ip → resource.port)`, not on `(peer.ip → site.cidr)`. This is the access *granularity*.
- **Q2: Protocol awareness.** Stop SSH being tunneled over an "RDP-only" port. This is the access *kind*.

Q1 is satisfiable with nftables. Q2 requires deep packet inspection or an identity-aware L7 proxy (sshpiperd, Pomerium, Cloudflare Access). Q2 is a different architectural class and is the subject of much of this ADR's tradeoff analysis.

## Decision

Two decisions, one ADR because they're tightly coupled:

**1. ACL granularity is `(peer.assignedIP, resource.ip, transport, port)`**, generated from explicit `RoleResourceGrant`s. Site CIDR is for routing (`AllowedIPs` in the peer's `.conf`) and for grouping resources in the UI; it is never the target of an nftables rule.

**2. Role-Based Access Control following NIST RBAC0**, i.e. Core RBAC: users assigned to roles, permissions assigned to roles, no role hierarchy and no constraints in v1. RBAC1/2/3 features (hierarchy, separation-of-duties, mutual exclusion) are explicit v2+ work, not blockers for v1.

Entity shape lives in [docs/prd.md §7](../prd.md#7-domain-model). The TL;DR:

- `User` — `*..*` — `Role`
- `Role` — `1..*` — `RoleResourceGrant`
- `RoleResourceGrant` — `1..1` — `Resource` (+ optional `*..*` to `ResourcePort` if `allPorts=false`)
- `Resource` — `1..1` — `Site` (organisational only)

### What this does NOT protect against

Stated up-front so future readers don't expect more than the model delivers:

- **Tunneled protocols on the wrong port.** An "RDP-only" grant to Terminal-01 port 3389 permits TCP packets to 10.20.0.5:3389. SSH-over-port-3389 is not detected. This is the L4 vs. L7 boundary. We do not claim otherwise. If the threat model requires L7 enforcement, an L7 proxy (e.g. Pomerium, sshpiperd, Teleport) must be added — that's an additional architecture, not a configuration of this one.
- **Lateral movement once on the target.** If Vertrieb has RDP into Terminal-01, and Terminal-01 has its own credentials to NAS-01, Islandr cannot help. We control whether the *tunnel* permits the connection, not what the target machine does next.
- **Compromised peer endpoint.** If a peer device is compromised the attacker inherits exactly the grants Islandr issued — that's the whole point of the model. Mitigation is per-peer disable + key rotation in Islandr, plus device-level controls outside our scope.

These are not bugs to fix later. They are the line we draw between "VPN access control" and "Zero Trust application proxy."

## Alternatives considered (Pugh Matrix)

Two scoring tables. The first is **ACL granularity**: where on the network stack do we filter. The second is **access modelling**: how do permissions attach to users.

### ACL granularity

Baseline: **per-resource (`peer.ip → resource.ip:port`), L4 only** (the decision).

| Criterion (weight)                              | Per-resource L4 (baseline) | Site-CIDR only | Per-resource L7 (identity-aware proxy) | Per-resource L4 + L7 hybrid |
|-------------------------------------------------|----------------------------|----------------|-----------------------------------------|------------------------------|
| Least-privilege fit (5)                         | 0                          | -1             | +1                                      | +1                           |
| End-user-portal clarity ("RDP zu Terminal-01") (4) | 0                       | -1             | 0                                       | 0                            |
| nftables-native enforcement ([ADR-0003](0003-nftables-replaces-ufw.md)) (4) | 0   | 0              | -1                                      | -1                           |
| Single-binary deployment ([ADR-0001](0001-quarkus-backend.md)) (4) | 0          | 0              | -1                                      | -1                           |
| Time-to-v1 (3)                                  | 0                          | +1             | -1                                      | -1                           |
| Protects against tunneled-protocol abuse (3)    | 0                          | -1             | +1                                      | +1                           |
| Operator mental model ("I configure a firewall") (2) | 0                     | +1             | -1                                      | -1                           |
| **Weighted total**                              | **0**                      | **−2**         | **−4**                                  | **−4**                       |

Honest reading:

- **Site-CIDR only** is the wg-easy default and the path of least operational resistance. We reject it because the resulting product can't say anything precise to the end user about *what* they can reach, and admin audit ("who reaches the switch?") collapses to "everyone in the role that owns the site."
- **Per-resource L7** (Pomerium-style identity-aware proxy) is the technically purest answer to Q2. We reject it for v1 because it requires a second runtime component on the hub, breaks the single-binary story, and pushes the whole product into a different category. **Acknowledged as a possible v3 evolution**, not a v1 blocker.
- **L4 + L7 hybrid** is what mature products (Tailscale Subnet Router + their TS-SSH, Twingate) converge on after years. v1 has neither the runtime budget nor the user maturity to justify it.

The −2 for the baseline against "tunneled-protocol abuse" is real and is the cost we explicitly buy by staying L4. Future revisits should be framed as "do we now need the L7 layer too" rather than "should we ever have done L4."

### Access modelling

Baseline: **NIST RBAC0** (the decision).

| Criterion (weight)                                  | RBAC0 (baseline) | Direct user-permission lists | ABAC (attribute-based) | RBAC1 (with role hierarchy) |
|-----------------------------------------------------|------------------|------------------------------|------------------------|------------------------------|
| Scale (100 users × 50 resources) (4)                | 0                | -1                           | 0                      | 0                            |
| Audit clarity ("who reaches X?") (4)                | 0                | -1                           | -1                     | 0                            |
| New-employee onboarding effort (3)                  | 0                | -1                           | 0                      | +1                           |
| Standard-pattern recognisability (NIST/AWS/K8s) (3) | 0                | 0                            | 0                      | 0                            |
| Time-to-v1 (3)                                      | 0                | +1                           | -1                     | -1                           |
| Schema migration risk later (2)                     | 0                | -1                           | -1                     | 0                            |
| Expressive power for unusual policies (2)           | 0                | -1                           | +1                     | +1                           |
| **Weighted total**                                  | **0**            | **−10**                      | **−4**                 | **+1**                       |

Honest reading:

- **Direct user-permission lists** are what gets built when "roles" are dismissed as overhead. They work for ≤10 users and become an audit nightmare after that. Clear reject.
- **ABAC** (e.g. "permit if user.department == 'sales' AND resource.tag == 'sales-tools'") is more expressive but pays for it in complexity. Recompute is policy-engine-evaluation per peer per resource per change. Real value emerges when policies need attributes from multiple sources (HR, asset DB, time-of-day). For v1 we have one source (Islandr's own DB) and policies map cleanly to explicit grants.
- **RBAC1** (with role hierarchy) scores +1 because for the IT-Admin-inherits-from-Engineer case it's strictly nicer. We defer it because (a) we don't have evidence yet that the v1 customer base needs it — a small operator with 3 roles doesn't, (b) hierarchy adds resolution cost on every recompute, and (c) the migration from RBAC0 to RBAC1 is additive (new join table, existing data untouched) so we're not painting ourselves into a corner.

The matrix says RBAC1 would be slightly better even now. We choose to ship RBAC0 in v1 and re-open this question if and when the first operator asks for hierarchy. That is a deliberate v1-scope decision, not a Pugh-overruling.

## Consequences

**Positive**

- Operators model access in the same vocabulary they use when talking to colleagues ("Vertrieb darf RDP auf Terminal-01"). UI matches mental model.
- End-user portal can render meaningful access lists per [F-18](../prd.md#self-service): "Terminal-01 — RDP" with the site for grouping, never raw CIDR.
- nftables ruleset has one rule per `(peer.ip, resource.ip, transport, port)` tuple — boring, explicit, easy to grep, validates cleanly with `nft -c -f` ([ADR-0003](0003-nftables-replaces-ufw.md)).
- Adding a new employee is one click ("add to role"), not N×M clicks.
- Audit query "who can reach Terminal-01?" is a two-join SQL query, not a graph walk.

**Risks created**

- **R-050** — Ruleset size grows linearly with `(enabled peers × granted ports)`. At 200 peers × 20 average granted ports = 4000 accept rules per ruleset. nftables handles this fine (sub-second `nft -f` apply) but the figure should be tracked. Mitigation: count rules on every reload; warn in UI above a configurable threshold (default 10k).
- **R-051** — The L4-only model invites a "but RDP is enforced, right?" misunderstanding. Mitigation: the Admin Console's grant editor labels the protocol field as "Anzeige im Portal" (display in portal), not as a security control. Tooltip and docs name the L4/L7 boundary explicitly.
- **R-052** — Resource maintenance falls on the admin (new server → add resource + ports). There's no auto-discovery in v1. Mitigation: an import API endpoint that takes a JSON/CSV of `(site, ip, name, ports)` so power-users can script bulk additions.
- **R-053** — Sites with many resources produce wide UIs (the Roles × Resources matrix gets tall). Mitigation: matrix is per-site (one tab per site, default to "most-granted site"), not a single 1000-row table.
- **R-054** — A poorly-scoped grant (e.g. ⓐ-all-ports on a resource with many future ports) silently widens access when admins later add ports. Mitigation: UI shows "wird automatisch erweitert wenn neue Ports hinzukommen" warning on ⓐ-grants; audit logs "port added to resource X, role Y had ⓐ grant → 3 new accept rules" with a diff.

**Accepted trade-offs**

- L7 enforcement (sshpiperd / identity-aware proxy) is **explicitly not** in v1. Operators who need it must add their own layer on top of the tunnel.
- No role hierarchy in v1. Power-users must duplicate grants across roles. We expect this to be tolerable for the v1 customer profile (≤80 users, ≤10 roles).
- Resource definitions are manual. No scan, no integration with asset management systems.

## References

- [NIST INCITS 359-2004 — RBAC standard](https://csrc.nist.gov/projects/role-based-access-control)
- [Sandhu et al. 1996 — original RBAC96 paper](https://profsandhu.com/articles/advcom/adv_comp_rbac.pdf)
- [docs/prd.md §7](../prd.md#7-domain-model) — full entity schema
- [docs/prd.md §5](../prd.md#5-scope--functional-requirements) — F-06 through F-08a, F-19
- [docs/adr/0003-nftables-replaces-ufw.md](0003-nftables-replaces-ufw.md) — what enforcement looks like on the hub
- [docs/adr/0005-hub-only-firewall.md](0005-hub-only-firewall.md) — why all enforcement is at the hub
- [Pomerium](https://www.pomerium.com/), [Teleport](https://goteleport.com/), [Tailscale ACLs](https://tailscale.com/kb/1018/acls) — examples of the L7-enforcement direction this ADR explicitly defers
