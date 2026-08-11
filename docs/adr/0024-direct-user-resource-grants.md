# ADR-0024 — Direct User-Resource grants, alongside Role-Resource grants

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0006](0006-resource-level-acl.md) (the per-resource Role grant model this extends), [ADR-0022](0022-acl-type-grants.md) (the additive-expansion pattern this follows for a second grant type)

## Context

The ACL model (ADR-0006) is exclusively Role-based: `role_resource_grants(role_id, resource_id, all_ports)`, with access reaching a person only via `user_roles` membership. Building the Atlas view (a graph of "what can this user reach", with drag-to-grant) surfaced a real gap: an admin who wants to grant one specific person access to one specific resource — a one-off, not a role-wide policy — has no way to do that without either creating a role that exists for exactly one member, or widening an existing role's membership for a single resource that role shouldn't otherwise carry. Neither is the actual intent; both pollute the role model with entries that don't represent a reusable policy.

A second, related gap the Atlas rework exposed directly: access has always been evaluated per-`User` (every `Peer.userId`-owned device inherits the same role-derived access — confirmed in `RuleBuilder.java`, which looks up `rolesByUser.get(peer.userId)` per peer, not per peer). The Atlas view's "drag from a Peer" interaction was therefore misleading — it visually implied per-device granularity that doesn't exist. This ADR's grant model is deliberately User-scoped, not Peer-scoped, matching the access model that has been true all along.

## Decision

**A new `user_resource_grants(user_id, resource_id, all_ports)` table**, structurally identical to `role_resource_grants` (plus its own `user_resource_grant_ports` join table for limited-port grants) — same tri-state semantics (∅ / all-ports / N specific ports) as the existing Role grant, just keyed by `user_id` instead of `role_id`.

**Expansion, not a parallel enforcement path** — the exact pattern ADR-0022 established for type-grants. Every consumer of `role_resource_grants` gets a small additive union with `user_resource_grants`, rather than a second, separately-maintained access-control code path:

- `RuleBuilder`: for each peer, in addition to role-derived grants for `rolesByUser.get(peer.userId)`, also resolve direct grants for `peer.userId` and render them into the same allow-rule loop — zero changes to the rule-emission logic itself.
- `AclResolutionService.resolveMyAccess`: a third query (`user_resource_grants` for the user) unions into the same `effective` map that role-grants and type-grants already populate — all-ports-wins, same as the existing two sources.
- `RdpGrantService.hasGrant`: a third check alongside the existing role-grant and type-grant checks.
- `ConfigService` export/import: a new `UserGrantSnapshot` list, following the exact pattern already used for `GrantSnapshot`/`GrantPortLink`.

**A new dedicated endpoint, not a matrix extension** — `PUT /api/v1/acl/user-grants`, single-grant apply (not batch, unlike the Role matrix): body `{userId, resourceId, allPorts, portIds}`, same tri-state create/update/delete-if-empty semantics as `RoleService.applyMatrix`'s per-cell logic, via a new `UserGrantService.apply(...)`. Single-grant rather than batch because the only caller is the Atlas view's drag-to-grant/click-to-revoke, which always acts on exactly one grant at a time — there is no "user-grants matrix" UI this needs to batch-save.

**Atlas view becomes User-Grant's primary UI**, and is reframed to match the User-scoped model directly: no per-user selector — the graph shows every User (including ones with zero peers, since a grant can be created before any device exists) and every Resource at once. A role-selector at the top of the view sets which grant kind a drag creates: a role selected → the drag creates/edits a Role grant (existing `PUT /api/v1/acl/matrix` path, unchanged) and highlights that role's members; no role selected (labeled explicitly, not a silent default) → the drag creates a direct User grant via this ADR's new endpoint. Each rendered edge carries a `kind` (`role` | `type-grant` | `user-direct`) so revoke routes to the correct endpoint — a `type-grant` edge stays blocked from this view exactly as ADR-0022 already established, since it has no per-resource row to remove.

## Alternatives considered (Pugh Matrix)

Baseline: **A — new `user_resource_grants` table, expansion-not-parallel-path** (the decision).

| Criterion (weight) | A: direct user-grant table (baseline) | B: synthetic hidden 1:1 role per user | C: per-peer grant (grant tied to one device, not the user) |
|---|:---:|:---:|:---:|
| Matches the actual access model (access is per-User, not per-Peer) (5) | +1 | +1 | −1 *(reintroduces the exact per-device granularity that doesn't exist today — `RuleBuilder` would need a real behavior change, not just a new grant source)* |
| Enforcement-path simplicity (5) | 0 | −1 *(a second, implicit "role" concept alongside real admin-created roles — role list/audit log would show synthetic entries no admin created)* | −1 *(new dimension: `RuleBuilder`'s per-peer rule loop would need per-device grant resolution, not just per-user)* |
| Implementation effort (4) | 0 | 0 *(no new table, but hidden-role bookkeeping — create-on-first-use, garbage-collect-on-last-grant-removed — is its own non-trivial lifecycle)* | −1 *(touches `RuleBuilder`'s core peer-role resolution, the highest-blast-radius file in the ACL system)* |
| Auditability / admin clarity (4) | +1 *(a `UserGrant:` audit-log subject line is exactly what happened)* | −1 *(audit log would read "role.grant_create" for a role the admin never knowingly created)* | 0 |
| **Weighted total** | **9** | **−5** | **−11** |

- **B (synthetic hidden role)** was the option floated during the original Atlas-view brainstorm and initially deferred rather than rejected — revisited here now that a real second use case (Atlas's drag-to-grant needing an explicit "just this person" choice) confirmed the need is real, not hypothetical. It loses mainly on honesty: role membership, the role list, and the audit log all become partially synthetic, which conflicts with the project's "admin-controlled, transparent RBAC" positioning more than a second explicit table does.
- **C (per-peer grant)** is rejected outright — it would relitigate the "access is per-User" model this ADR's Context section explicitly confirms is already true and unchanged everywhere else in the codebase (`RuleBuilder`, `MyAccessResource`, role membership). Building it would be solving a problem that was never actually reported.

## Consequences

- Four files gain a small, additive read step: `RuleBuilder.java`, `AclResolutionService.java`, `RdpGrantService.java`, `ConfigService.java` — same shape as ADR-0022's four-file extension.
- New migration: `user_resource_grants`, `user_resource_grant_ports` tables.
- New REST resource `AclUserGrantResource` (`PUT /api/v1/acl/user-grants`), new `UserGrantService`.
- Atlas view (backend endpoint `GET /api/v1/acl/atlas`, frontend `AtlasView.js`/`AtlasDiagram.js`) is reframed from "one selected user's reach" to a global User×Resource graph — the largest consequence of this ADR outside the grant model itself, tracked and implemented alongside it rather than as a follow-up, since the direct-grant use case only makes sense in that global shape (a per-user-scoped Atlas view has no natural place to show "grant this other person access" without first navigating to them).
- **R-180** — a direct User grant, like a Role grant, has no notion of "temporary" or "self-service" (TODO.md's parked "self-service peer-to-peer sharing" idea remains unrelated and unresolved by this ADR — that idea was about Peer-to-Peer reachability across the firewall's `chain forward` policy, a different problem this ADR does not touch).
- Follow-up work, deliberately out of this ADR's scope: a dedicated admin-facing list/management view for direct user-grants outside of Atlas (today, Atlas's drag/revoke is the only UI — acceptable since Atlas is the primary and, for now, only surface this grant type needs).

## References

- [ADR-0006](0006-resource-level-acl.md) — the per-resource Role grant model this ADR adds a second, parallel subject type alongside.
- [ADR-0022](0022-acl-type-grants.md) — the additive-expansion pattern (four-consumer small-read-step extension, not a parallel enforcement path) this ADR follows exactly for a different grant subject.
- `UserResourceGrant.java`, `UserGrantService.java`, `AclUserGrantResource.java`, `AclResolutionService.java`, `RuleBuilder.java`, `RdpGrantService.java`, `ConfigService.java` — the implementation.
- `docs/superpowers/specs/2026-08-11-atlas-view-design.md` (amended) — the Atlas view's global-graph reframe that this grant type's UI depends on.
