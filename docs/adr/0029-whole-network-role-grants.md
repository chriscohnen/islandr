# ADR-0029 — Whole-network role grants: one nftables rule per site CIDR, not per resource

**Status:** Accepted
**Date:** 2026-09-05
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0006](0006-resource-level-acl.md) (the per-resource grant model this extends), [ADR-0022](0022-acl-type-grants.md) (the type-grant precedent this mirrors on the enforcement side), [ADR-0024](0024-direct-user-resource-grants.md) (the other model this deliberately does *not* mirror — see Decision)

## Context

Type-grants ([ADR-0022](0022-acl-type-grants.md)) let a role reach every resource of **one** chosen type in a site ("all printers in Homeoffice"), live-resolved so a printer added later is covered automatically. Issue #78 identified two real gaps remaining for an admin who owns a whole network and wants to hand a group full access to it without per-resource upkeep:

1. **No "all types" option** — a mixed network (printer + NAS + computer + camera...) needs one type-grant per type present, and still misses a brand-new type introduced later.
2. **Still one nftables rule per resource, not per network, even hypothetically.** `RuleBuilder.java` was read end to end for this decision: every grant kind that exists today — concrete resource grants, type-grants (expanded into synthetic per-resource grants at generation time), direct user-grants ([ADR-0024](0024-direct-user-resource-grants.md)), direct site-grants (`SiteResourceGrant`) — ultimately calls the same `emitRulesForGrant` helper with one specific **resource IP** as the destination (`daddr`). Nothing emits a single rule with a **site's CIDR** as the destination. Even a hypothetical "all types" type-grant would still expand into N rules (one per matching resource × peer), not the "one entry for the whole network" an admin who owns that network actually wants.

`SiteResourceGrant` already made `Site.cidr` participate in nftables generation once, as a **source** (a deliberate, narrow exception to `Site.cidr` otherwise being purely informational, per ADR-0006). This ADR is the same exception in the **destination** direction, so it gets the same Pugh-matrix scrutiny ADR-0022 gave the type-grant decision before writing code.

## Decision

**A new `role_network_grants(role_id, site_id)` table** — no `all_ports` column, no port-link join table, following ADR-0022's exact reasoning for why a CIDR-wide rule can't sensibly carry a fixed port list against resources whose actual ports vary: it is unconditionally full-reach, all ports, all protocols (TCP+UDP+ICMP+everything), the same permissiveness ADR-0022 chose for type-grants taken one step further. A row means "every peer of every user holding this role can reach every host in this site's subnet, on every protocol/port, present or future."

**Role-only, no direct-user variant.** Unlike `SiteResourceGrant`/`UserResourceGrant` (ADR-0024), which exist in both a role-derived and a direct-user-subject form, this grant stays role-only — matching `RoleResourceTypeGrant`, which has no user-direct counterpart either. A direct-user network grant is a plausible future ADR if actually requested; nothing in this schema blocks adding one later.

**Enforcement — a dedicated emission path, not an `emitRulesForGrant` extension.** `emitRulesForGrant` is resource-and-port shaped (it resolves a `Resource`, filters its `ResourcePort`s, and renders one rule per granted port). A bare `daddr <cidr> accept` with no resource and no port clause does not fit that shape, so `RuleBuilder` gets a new, self-contained loop — structurally the mirror of the existing direct-site-grant block (which already runs once per `Site` rather than nested in the per-peer loop, for the same reason: it isn't resource-keyed): for each `RoleNetworkGrant`, for every peer whose user holds that role, emit one `iifname wg0 ip saddr <peerIp> ip daddr <site.cidr> accept` (and the IPv6 equivalent when the site has one) — no `tcp`/`udp` clause, no `dport`, so ICMP is covered by the same rule and no separate implicit-ICMP entry is needed for it (unlike every other grant kind in `RuleBuilder`, which does emit a separate ICMP accept per (peer, resource) pair).

**Display/access-resolution — the same union pattern ADR-0022 established**, not a parallel model:
- `AclResolutionService` (backs `MyAccessResource`, the portal's "what can I reach"): unions in every resource whose `site_id` matches a network-granted site, all-ports — same shape as its existing type-grant union.
- `RdpGrantService.hasGrant`: new `hasNetworkGrant(userId, resourceId)` check alongside the existing `hasTypeGrant`, joining `role_network_grants` → `user_roles` → `resources.site_id`. Inherits the same R-171 gap `hasTypeGrant` already has (no `auto_all`/Everyone union) — not fixed here, for the same "keep this ADR's diff scoped" reason ADR-0022 gave.
- `ConfigService` export/import: a new `NetworkGrantSnapshot` list, same delete-then-reinsert pattern as `TypeGrantSnapshot`.
- **Atlas view** (the admin's global grant graph): a network grant is not resource-keyed, so it cannot render as an edge-to-a-resource-node the way every existing edge kind does. `AtlasDto.Edge` gains a nullable `siteId` field and a new `kind = "network-grant"`; `AclResolutionService.buildAtlasGraph()` fans it out to users exactly like the type-grant block does. `AtlasDiagram.js` resolves this edge kind's target as the site's own circle (the point on its rim nearest the source), not a node inside it, and gives that circle a distinct stroke — but only while the edge is actually visible under the existing selected-user/role-highlight filter, the same visibility rule every other edge kind already follows. This is additive rendering logic, not a new architectural surface, but is called out here because it's the one place this ADR's decision is user-visible outside the ACL panel itself.

**A third panel on Roles & ACL**, "Netzwerk-Freigaben", alongside the matrix and the existing "Typ-Freigaben" panel, scoped to the active site tab, immediate-effect (no dirty-state staging) — same UI shape ADR-0022 chose for type-grants and for the same reason (each row is a single atomic create/delete, not a value being edited).

## Alternatives considered (Pugh Matrix)

Baseline: **A — new table, role-only, CIDR-destination rule, expansion-not-parallel-path** (the decision).

| Criterion (weight) | A: whole-network grant (baseline) | B: "all types" type-grant (no CIDR collapse) | C: materialize into concrete `role_resource_grants` rows at write time |
|---|:---:|:---:|:---:|
| Solves the actual pain point ("no re-grant needed when a resource is added") (5) | +1 *(new resources need zero new rules — the CIDR is already open)* | 0 *(still needs a rule regen per new resource, just automatic instead of manual)* | −1 *(a newly-added resource is invisible until something re-runs materialization — ADR-0022 rejected this exact alternative for the same reason)* |
| Ruleset size / firewall footprint (4) | +1 *(one rule per peer per site, independent of resource count)* | −1 *(N rules per matching resource × peer, same as today, just harder to forget)* | −1 *(same N-rules-per-resource footprint as B)* |
| Enforcement-path simplicity (4) | 0 *(one new dedicated loop in RuleBuilder, but a genuinely new rule shape — bare CIDR, no ports)* | +1 *(reuses the existing type-grant expansion verbatim, zero new RuleBuilder code)* | −1 *(needs a resource-added/removed hook to keep materialized rows in sync, the same hazard ADR-0022 flagged for its own alternative D)* |
| Blast radius if misused (5) | −1 *(full L3/L4 to the entire subnet, including hosts Islandr has never seen — see R-189 below)* | 0 *(bounded to resources Islandr actually knows about, still all-ports per resource)* | 0 *(same bound as B)* |
| Matches the actual request ("one rule for the whole network") (4) | +1 | −1 *(explicitly not what was asked — still N resource-scoped rules)* | 0 *(gets the rule-count win only if paired with a CIDR-collapse step B doesn't have either)* |
| **Weighted total** | **6** | **−5** | **−12** |

- **B ("all types" type-grant)** closes gap 1 from the issue (a mixed-type network needs one type-grant per type present) cheaply, and is worth doing regardless — but it does not close gap 2 (still per-resource rules) at all, which is the reason issue #78 exists as a separate feature rather than a small addition to ADR-0022's existing model. Not mutually exclusive with A; an "all types" type-grant remains a valid, smaller follow-up if the CIDR-collapse permissiveness of A is ever judged too broad for a given deployment.
- **C (materialize at write time)** repeats the exact mistake ADR-0022's own alternative D was rejected for, with no offsetting benefit — it still doesn't collapse to one rule per site the way A does.

## Consequences

- Five files gain additive changes: `RuleBuilder.java` (new dedicated loop), `AclResolutionService.java` (new union block, both in `buildAtlasGraph` and the portal-access resolution), `RdpGrantService.java` (`hasNetworkGrant`), `ConfigService.java` (`NetworkGrantSnapshot`), `AtlasDto.java` (`siteId` on `Edge`).
- A new REST resource, `AclNetworkGrantResource` (`/api/v1/acl/network-grants`), a new small UI panel on `AclMatrixView.js`, and a new edge-rendering case plus circle-highlight in `AtlasDiagram.js`.
- **R-189** (new, added to [Chapter 11](../arc42/11-risks-and-technical-debt.md)) — a whole-network grant is coarser than even a type-grant: full L3/L4 reach to every host in the subnet, including devices Islandr has never registered as a `Resource` at all. An admin who reaches for this panel is accepting that scope explicitly, the same way `SiteResourceGrant` already accepted the equivalent risk on the source side — but the destination-side version is worse, since the *set of reachable hosts* is now unbounded by Islandr's own inventory, not just the set of *subjects*. Mitigated by: the panel's own naming and any accompanying UI copy make the "whole network, not just known resources" scope explicit before creation; every create/delete is audit-logged (`grant.network.create`/`grant.network.delete`, same pattern as `grant.type.create`/`grant.type.delete`); this is an admin-only action requiring an explicit, deliberate panel interaction, not a default or an accidental widening the way R-054's port-addition case is.
- Inherits R-171 (RdpGrantService's `hasTypeGrant`/now also `hasNetworkGrant` do not union `auto_all` roles) without fixing it — same scoping call ADR-0022 made.
- Follow-up work, deliberately out of this ADR's scope: an "all types" type-grant (Alternative B) as its own smaller feature if the full-network permissiveness of this ADR is ever too broad for a given site; a direct-user (non-role) variant, only if requested.

## References

- [ADR-0006](0006-resource-level-acl.md) — the per-resource grant model.
- [ADR-0022](0022-acl-type-grants.md) — the type-grant precedent this ADR's enforcement/display/UI shape mirrors throughout.
- [ADR-0024](0024-direct-user-resource-grants.md) — the role/direct-user split this ADR deliberately does not replicate.
- Issue #78 — the original request and the `RuleBuilder.java` read-through that established gap 2.
- `RoleNetworkGrant.java`, `AclNetworkGrantResource.java`, `RuleBuilder.java`, `AclResolutionService.java`, `RdpGrantService.java`, `AtlasDiagram.js` — the implementation.
- Migration `V75__role_network_grants.sql` — schema.
