# ADR-0017 — Split tunnel lists all known networks, not just the peer's current grants

**Status:** Proposed
**Date:** 2026-07-19
**Deciders:** Christian Cohnen
**Relates to:** [F-22](../prd.md) (the requirement this backs), [ADR-0006](0006-resource-level-acl.md) (resource-level ACL / RBAC0 — the grant model this deliberately does *not* feed into `AllowedIPs`), [ADR-0005](0005-hub-only-firewall.md) (enforcement stays on the hub, not in what's routed to the client), `peer/PeerService.java:539-580` (`renderConf`, where `AllowedIPs` is built today)

## Context

`AllowedIPs` on the client `[Peer]` config block controls two different things at once, and it's important not to conflate them:

1. **Routing** — which destination networks the client's OS sends through the WireGuard tunnel at all.
2. **Access** — which of the hosts reachable via that routing the client is actually allowed to talk to.

Today, (1) is a single global free-text field (`Settings.wgClientAllowedIps`) with every enabled gateway peer's site CIDR appended automatically, uniformly, to every client — there is no full/split distinction and no way to reason about what "split" should even mean. (2) is handled correctly and separately: nftables rules computed from `RoleResourceGrant` at the hub (F-08, [ADR-0006](0006-resource-level-acl.md)) — a client can route toward a network and still have every packet into it dropped at the hub if no grant covers it.

The open question, raised when scoping split tunnel as a real feature (not just "whatever string the admin types"), was: should the *routed* network list for a client be **derived from that peer's current role grants** (route only to sites/subnets the peer's role can currently reach something in), or should it be a **fixed list of every known network**, independent of grants, with only a binary full-vs-split choice left to the admin?

The grant-derived option was the initial instinct — it reads as "tighter," routing nothing the peer can't use anyway. It has a real operational cost: every role-grant change (a resource added to a site, a grant added or revoked, a new site created) would need to reconsider, and potentially rewrite, the `AllowedIPs` line of every peer whose role touches that change. A changed `AllowedIPs` line means an already-provisioned client — a phone that scanned a QR code weeks ago — no longer has a locally correct config until it re-imports. WireGuard has no live config-push mechanism to a road-warrior client; the only way to update `AllowedIPs` on a device is to give the user a new `.conf` or QR code to import by hand, or to run a management daemon on every client to do it automatically ([`wg-quick` does not poll](https://www.wireguard.com/), Islandr does not run a client-side agent). ACL grants are expected to change routinely as an organisation's access needs evolve — that is the entire point of role-based grants existing separately from static peer config. Tying `AllowedIPs` to grants means routine ACL administration silently breaks already-deployed client configs.

## Decision

**`AllowedIPs` for a client peer lists every known network — the VPN subnet plus every site's CIDR with an enabled gateway peer — regardless of what that peer's role is currently granted.** The only per-deployment choice is binary: **full tunnel** (`0.0.0.0/0`) or **split tunnel** (the fixed network list above). Role-grant changes never touch `AllowedIPs` and therefore never require a peer to re-import its config. Access enforcement inside those routed networks remains entirely the hub firewall's job (F-08) — unchanged from today, and the reason this is safe: a peer routing toward a site it has no grant in still can't reach anything there, because nftables drops it at the hub. `AllowedIPs` answers "where might traffic go," never "where is traffic allowed" — that second question already has a correct, separate answer.

### What changes vs. today

- Today: one global free-text CIDR string, admin-typed, with a manual chance of drifting from what sites actually exist.
- Decision: the split-tunnel network list is *computed* — VPN subnet + CIDR of every site with an enabled gateway peer — not typed, so it can never miss a site or carry a stale one. The full-vs-split choice becomes an explicit setting rather than "whatever the admin happened to type into the CIDR field."

### What does not change

- Enforcement path (F-08, nftables from `RoleResourceGrant`) — completely untouched by this decision.
- A peer's actual reachable hosts are still exactly what its role grants say, at all times — split tunnel only affects the outer routing boundary, not the inner authorization boundary.

## Alternatives considered (Pugh Matrix)

Baseline: **A — fixed network list (VPN subnet + all gateway-backed site CIDRs), binary full/split toggle** (the decision).

| Criterion (weight) | A: fixed list, binary toggle (baseline) | B: grant-derived AllowedIPs per peer | C: keep today's free-text global field, no toggle |
|---|:---:|:---:|:---:|
| Client config stays valid across ACL changes (5) | 0 | −1 | 0 |
| Consistency with hub-only enforcement model (4) | 0 | −1 | 0 |
| Implementation effort (3) | 0 | −1 | +1 |
| Operator predictability ("what will this peer route to") (3) | 0 | 0 | −1 |
| Minimizes what's routed toward the client, defense-in-depth (2) | 0 | +1 | 0 |
| **Weighted total** | **0** | **−7** | **0\*** |

\* C ties A on the weighted total but loses on the criterion this ADR exists to fix (predictability / the full-vs-split concept not existing at all) — included for completeness, not as a live contender.

- **B (grant-derived)** loses on the two highest-weighted criteria for the reason laid out in Context: every grant change becomes a potential client-config-breaking event, with no mechanism to push the updated config to an already-provisioned device. It also blurs the enforcement model this project has been deliberate about since [ADR-0005](0005-hub-only-firewall.md) and [ADR-0006](0006-resource-level-acl.md) — access control belongs at the hub firewall, not in what's routed to the client. Its one advantage (defense-in-depth: a compromised client can't even route toward networks it has no grant in, only toward ones it does) is real but secondary to a WireGuard hub whose firewall is the actual, audited enforcement point — a client routing toward a network it can't reach anything in is a no-op, not a hole.
- **C (status quo)** costs nothing to keep but leaves the actual problem — "what does full vs. split even mean here, and does it drift from reality" — unsolved. Rejected because it doesn't answer the requirement, not because it scores badly.

A wins because it treats `AllowedIPs` purely as a routing-scope decision, decoupled from the authorization model that already lives correctly at the hub, and because it never requires re-provisioning a device over an ACL change — which, for role-based access that is expected to change often, is the property that actually matters.

## Consequences

**Positive**

- ACL administration (grants, roles, resources) never invalidates an already-deployed client `.conf`/QR code — the operational cost this ADR exists to avoid.
- The split-tunnel network list is derived, not hand-typed, so it can't drift from the actual set of sites.
- No change to the enforcement path or its audit trail — the hub firewall remains the single place access decisions are made and logged.

**Risks created**

- A client's OS-level routing table includes every site CIDR even for sites it has no grant in. This is not an access risk (nftables blocks it), but it is mild network-topology disclosure to the client OS (the CIDRs themselves are visible on the device, e.g. via `wg show`/`ip route`, even though nothing is reachable there). Accepted: a road-warrior client already trusts the hub with far more (its own assigned IP, hub public key); the CIDR list is not sensitive by itself, and this is the standard WireGuard `AllowedIPs`-as-routing-scope pattern documented for the tool.
- Split tunnel does not shrink a device's exposure the way grant-derived routing marginally would. Accepted per the Alternatives analysis: real exposure control is nftables, not `AllowedIPs`.

**Accepted trade-offs**

- No per-peer, per-grant-scoped `AllowedIPs` in v1. If a genuine future need for OS-level routing minimization per peer emerges (e.g. a hard regulatory requirement, not just "feels tighter"), that is a new, separate decision — this ADR's baseline does not preclude one, but nothing in scope today asks for it.

## References

- [F-22](../prd.md) — the requirement this ADR backs.
- [ADR-0006](0006-resource-level-acl.md) — the resource-level ACL model that remains the sole access-control mechanism; `AllowedIPs` is deliberately not derived from it.
- [ADR-0005](0005-hub-only-firewall.md) — hub-only enforcement; the architectural reason routing scope and access scope are different questions.
- `peer/PeerService.java:539-580` — `renderConf`, where `AllowedIPs` is assembled today (single global string + auto-appended site CIDRs).
