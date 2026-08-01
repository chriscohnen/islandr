# ADR-0022 — ACL grants by resource type within a site, additive-only, always all-ports

**Status:** Accepted
**Date:** 2026-07-28
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0006](0006-resource-level-acl.md) (the per-resource grant model this extends)

## Context

The ACL matrix ([ADR-0006](0006-resource-level-acl.md)) grants a role access to one concrete resource at a time — `role_resource_grants(role_id, resource_id, all_ports)`. Testing feedback on the 0.15.0 cycle flagged a real gap: an admin wants to grant a role "every printer in the Homeoffice site", not click through each printer individually, and — just as importantly — not have to remember to also grant every *future* printer added to that site later. The existing all-ports flag already solves the equivalent problem within one resource (a grant widens automatically as new ports are added to that resource, per ADR-0006's own R-054); this asks for the same "widens automatically" property one level up, across resources of a type.

Two scope questions were resolved by direct confirmation before implementation, both toward the simpler option:

- **Port granularity**: a type-grant is always all-ports. A per-type, per-port-subset rule (e.g. "all printers, but only port 631") was considered and rejected — it would mean applying a fixed port *list* against resources whose actual ports vary, with no clear behavior when a resource lacks a listed port. All-ports keeps a type-grant's meaning unambiguous: "this role can fully reach every resource of this type in this site."
- **Combination with existing resource-level grants**: additive-only. A type-grant can only *widen* access, never narrow or override a concrete per-resource grant. No exclusion mechanism ("all printers except this one") exists. If an admin needs an exception, they don't use a type-grant for that role/site/type at all — they grant the individual resources instead.

## Decision

**A new `role_resource_type_grants(role_id, site_id, resource_type)` table**, structurally simple (no port columns, no join table) because of the two scoping decisions above. A row means "this role can reach every resource matching this site + type, on every port, now and in the future" — no `all_ports` column needed since it's always true, no port-link join table needed since there's nothing to link.

**Expansion, not a parallel enforcement path.** Every consumer of `role_resource_grants` — `RuleBuilder` (nftables generation), `MyAccessResource` (self-service portal "what can I reach"), `RdpGrantService` (browser-RDP gate) — gets a small addition that resolves matching resources and treats them exactly like an existing all-ports grant, rather than a second, separately-maintained access-control code path:

- `RuleBuilder`: for each type-grant, find resources where `site_id` and `type` match, and append a synthetic (never-persisted) `RoleResourceGrant.createNew(roleId, resourceId, allPorts=true)` per match into the same `grantsByRole` map the rest of the method already renders. Zero changes to the actual rule-emission logic below that point.
- `MyAccessResource`: a second query resolves type-grant-matched resource IDs, unioned into the same `effective` map the concrete-grant query already builds (unconditional all-ports overwrite — safe, since all-ports is always the correct widest result regardless of whether a narrower grant already touched that resource).
- `RdpGrantService`: `hasGrant` first checks the existing concrete-grant path; if that's false, a second check (`hasTypeGrant`) looks for a type-grant whose site+type matches the resource. Mirrors the existing query's use of `user_roles` only (not auto_all/Everyone roles) — deliberately unchanged behavior, not a bug fix bundled into this feature.
- `ConfigService` export/import: a new `TypeGrantSnapshot` list, following the exact pattern already used for `GrantSnapshot`/`GrantPortLink` (delete-then-reinsert on import, in FK order after roles and sites).

**A separate small panel, not a matrix extension.** The existing ACL matrix ([ADR-0006](0006-resource-level-acl.md)) is fundamentally a grid keyed by (role, concrete resource) — a type-grant has no resourceId, so it isn't a matrix cell and doesn't fit the tri-state (∅/ⓐ/N) cell model. It renders as its own small list-plus-add-form panel under the matrix, scoped to whichever site tab is currently active (reusing the matrix's own site-selection state), with its own `POST`/`DELETE` REST resource (`AclTypeGrantResource`) rather than folding into `AclMatrixResource`'s batch-apply endpoint. Unlike the matrix's deliberate no-auto-save behavior, a type-grant add/remove takes effect immediately — there's no per-cell "dirty" state to stage since each row is a single atomic create/delete, not a value being edited.

## Alternatives considered (Pugh Matrix)

Baseline: **A — new table, always all-ports, additive-only, expansion-not-parallel-path** (the decision).

| Criterion (weight) | A: simple type-grant (baseline) | B: port-subset-capable type-grant | C: type-grant with per-resource exclusions | D: expand into concrete `role_resource_grants` rows at write time (materialized, not resolved live) |
|---|:---:|:---:|:---:|:---:|
| Enforcement-path simplicity (5) | 0 | −1 *(port list vs. varying per-resource ports has no clean semantics)* | −1 *(a second conflict-resolution model alongside additive-only)* | 0 |
| Implementation effort (4) | 0 | −1 *(new port-picker UI + validation for a rule that isn't resource-scoped)* | −1 *(exclusion table, UI, and a real "which wins" resolution order)* | −1 *(needs a resource-added/removed hook to keep materialized rows in sync)* |
| Correctness under resource churn (a printer added after the grant) (5) | +1 *(resolved live, always current)* | +1 | +1 | −1 *(a newly-added resource is invisible until something re-runs the materialization — the exact problem this feature exists to solve)* |
| Matches the actual request ("all printers, full stop") (4) | +1 | 0 *(solves a narrower-port case nobody asked for)* | 0 *(solves an exclusion case nobody asked for)* | +1 |
| **Weighted total** | **2** | **−2** | **−3** | **−1** |

- **B (port-subset-capable)** and **C (per-resource exclusion)** both add real complexity for cases outside the actual request — confirmed out of scope directly rather than guessed. Both are addressable later as their own ADR if a real need shows up; nothing in A's schema blocks adding either (a nullable port-list column or an exclusion table are both additive migrations).
- **D (materialize into concrete grant rows at resource-create time)** looks appealing because it needs zero changes to `RuleBuilder`/`MyAccessResource`/`RdpGrantService` — but it loses on the one criterion the whole feature exists for: a resource created after the type-grant needs an event hook (on `ResourceService.create`) to backfill a grant row, and a symmetric hook on delete to clean one up. Miss that hook once (a bulk import path, a future resource-creation route) and the type-grant silently stops working for resources created through it — resolving live against the current DB state, as A does, cannot go stale by construction.

## Consequences

- Four files gain a small, additive read-and-union step: `RuleBuilder.java`, `MyAccessResource.java`, `RdpGrantService.java`, `ConfigService.java` — each already had exactly one place where concrete grants are resolved, extended rather than restructured.
- A new REST resource, `AclTypeGrantResource` (`/api/v1/acl/type-grants`), and a new small UI panel in `AclMatrixView.js`, separate from the matrix's batch-apply/dirty-state flow.
- **R-170** — A type-grant is coarser than the matrix's own per-resource, per-port model; an admin who wants "all printers except the one in the server room, port 631 only" cannot express that with a type-grant and must fall back to individual resource grants for that role. Accepted per the additive-only/always-all-ports scoping decision — a real limitation, not an oversight, and addable later (Alternatives B/C above) if requested.
- **R-171** — `RdpGrantService.hasTypeGrant` mirrors the existing `hasGrant` query's `user_roles`-only join, which does not account for `auto_all` (Everyone) role membership — an existing, unrelated gap in the RDP-gate path (MyAccessResource's own grant resolution already unions in `auto_all` roles; RdpGrantService's does not, seemingly always). Deliberately not fixed here to keep this ADR's diff scoped to the type-grant feature; flagged for a separate look.
- Follow-up work, deliberately out of this ADR's scope: port-subset type-grants and per-resource exclusions (Alternatives B/C), only if a real request for either shows up; auditing/fixing R-171 as its own change.

## References

- [ADR-0006](0006-resource-level-acl.md) — the per-resource grant model and its all-ports R-054 precedent this ADR extends by one level (type, not just port-set).
- `RoleResourceTypeGrant.java`, `AclTypeGrantResource.java`, `RuleBuilder.java`, `MyAccessResource.java`, `RdpGrantService.java` — the implementation.
- Migration V51 — schema.
