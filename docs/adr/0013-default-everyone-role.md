# ADR-0013 — Default "Everyone" role with auto-membership

**Status:** Accepted
**Date:** 2026-07-10
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0006](0006-resource-level-acl.md) (RBAC0 model), [F-01b](../prd.md) (admin@local bootstrap seed).

## Context

A fresh install seeds an `admin@local` administrator ([F-01b](../prd.md)) so the operator has a usable identity, but it seeds **no roles**. RBAC0 ([ADR-0006](0006-resource-level-acl.md)) attaches every grant to a `Role`, and access resolves as `user → user_roles → role → RoleResourceGrant → resource`. So on a brand-new instance the operator cannot grant access to *anything* until they first create a role, then assign users to it. That is a cold-start papercut precisely at the moment a new operator is trying to see the product work.

Operators reach for a "group that contains everyone" — the shape Google Workspace (`all-users@`), Microsoft 365 ("Everyone"), and Slack (`@channel`) all ship. In Islandr's vocabulary there are **no groups**; the grouping primitive is the `Role`. So "Everyone" is a seeded default *role*, not a new entity.

The quick-start value only materialises under one condition: **new users must belong to "Everyone" automatically**. A pre-created but empty role that the admin still has to populate by hand is barely better than "create a role yourself" — the operator adds every user twice (once to Everyone, once to their real role) and every future user is silently outside it. The decision therefore hinges on *auto-membership*, not on the mere existence of a default role.

A sibling idea — a default **"admins" access-role** — is explicitly out of scope: application-level admin vs. end-user is already the fixed system-role enum `ADMIN`/`END_USER` ([F-19](../prd.md)). A second "admins" *access* role would duplicate that axis and invite confusion between "may reach the admin console" and "has an ACL grant". If a concrete grant-bundle for admins is ever needed, it is a normal role, created on demand — nothing special.

## Decision

**Seed one default role, `Everyone`, carrying an `autoAll` flag that makes every user an implicit member — present and future — without any `user_roles` rows.**

Concretely:

1. **Schema.** Add `auto_all BOOLEAN NOT NULL DEFAULT 0` to the `roles` table (Flyway migration). Only the seeded `Everyone` role has it set; ordinary roles never do (no UI to toggle it in v1).
2. **Seed.** `RoleBootstrap` idempotently creates the `Everyone` role (`auto_all = 1`) on first startup (unconditionally — unlike the ENV-gated `admin@local` seed). It is created **with zero grants** — inert until the admin deliberately grants something to it.
3. **Resolution.** Everywhere access is resolved from users, the effective role set for a user is `roles(user_roles) ∪ roles(auto_all = 1)`:
   - [`MyAccessResource`](../../src/main/java/de/chriscohnen/islandr/acl/MyAccessResource.java) — the portal "what can I reach?" query unions the auto-all roles.
   - [`RuleBuilder`](../../src/main/java/de/chriscohnen/islandr/firewall/RuleBuilder.java) — the firewall recompute adds every auto-all role's grants to every **user-linked** peer. Site/gateway peers have no `userId` and are unaffected (Everyone models *user* access, not routing).
   - [`AclMatrixResource`](../../src/main/java/de/chriscohnen/islandr/acl/AclMatrixResource.java) — the matrix shows `Everyone` like any role, but the UI labels its membership as "alle Nutzer (auch künftige)" rather than a member count.
4. **Lifecycle.** `Everyone` is a protected role: it cannot be deleted or renamed, and its `auto_all` flag cannot be cleared via the API — otherwise the contract "a grant on Everyone reaches all users" silently breaks. Grants on it are freely editable.
5. **Synergy with the bootstrap admin.** Because `admin@local` is a user, it is automatically covered by `Everyone`; the operator can grant a resource to `Everyone` and immediately reach it from their own peer, with no role plumbing.

## Alternatives considered (Pugh Matrix)

Baseline: **A — seeded `Everyone` role with `auto_all` auto-membership** (the decision).

| Criterion (weight)                                   | A: auto_all role (baseline) | B: plain pre-created role (manual membership) | C: no default role (status quo) | D: "applies to all" flag on the grant (no role) |
|------------------------------------------------------|:---------------------------:|:---------------------------------------------:|:-------------------------------:|:-----------------------------------------------:|
| Quick-start value on a fresh install (4)             | 0                           | -1                                            | -1                              | 0                                               |
| Covers future users automatically (4)                | 0                           | -1                                            | -1                              | 0                                               |
| Fits the existing RBAC0 model, no new concept (3)    | 0                           | 0                                             | 0                               | -1                                              |
| Least-privilege safety (harder to over-grant) (3)    | 0                           | +1                                            | +1                              | 0                                               |
| Implementation cost (2)                              | 0                           | +1                                            | +1                              | -1                                              |
| Audit clarity ("who reaches X?") (2)                 | 0                           | 0                                             | 0                               | -1                                              |
| **Weighted total**                                   | **0**                       | **−1**                                        | **−1**                          | **−7**                                          |

Honest reading:

- **B (plain pre-created role)** is safer (nothing is ever implicitly granted) and cheaper (no resolution change), but it forfeits the entire point: future users fall outside it and the admin maintains membership by hand. It scores −1 purely on the two quick-start criteria that motivated the ADR. If we did not care about future-user coverage, B would win — but then we would not seed a role at all (that is C).
- **C (status quo)** is the honest "do nothing"; it ties on the safety criteria and loses the quick-start ones. It is the fallback if we decide the papercut is not worth any implicit access.
- **D (a boolean on each grant, "applies to all users", no role)** avoids seeding anything but bolts a second authorization axis beside roles — grants now come in two flavours, the "who reaches X?" audit query must union two shapes, and the mental model splits. It is the worst on model-fit, cost, and audit. Clear reject.

A ties B and C at the weighted total because its **safety cost is real** (see R-130) and is the price for the quick-start gain. The tie is broken deliberately in favour of the operator experience, with the safety cost mitigated rather than denied — the same shape as the ADR-0006 ⓐ-all-ports decision (R-054).

## Consequences

**Positive**

- A fresh install can grant access immediately: seed → grant a resource to `Everyone` → every user (including `admin@local`) reaches it. No role plumbing for the first win.
- Future users are covered with zero action — the mental model matches Google/M365 "Everyone".
- No new entity or authorization axis: `Everyone` is an ordinary row in `roles` with one extra boolean; the RBAC0 story from [ADR-0006](0006-resource-level-acl.md) is intact.
- The bootstrap admin ([F-01b](../prd.md)) gains reachable access without being explicitly added to any role.

**Risks created**

- **R-130** — An `auto_all` role grants to **all users, present and future**. A careless grant on `Everyone` (especially ⓐ-all-ports, compounding [R-054](0006-resource-level-acl.md)) silently widens access to every current user and every user created afterwards — a least-privilege footgun. Mitigation: (1) `Everyone` is seeded **empty** (inert until a grant is added deliberately); (2) the ACL matrix labels the column "alle Nutzer (auch künftige)" and warns on any grant added to an `auto_all` role; (3) every grant change on `Everyone` is audited like any grant; (4) auto-membership is a fixed property of exactly one seeded role — there is no UI to mark arbitrary roles `auto_all`, so the blast radius cannot spread.
- **R-131** — If `Everyone` could be deleted, renamed, or have `auto_all` cleared, the "reaches all users" contract would break silently and existing grants would change meaning. Mitigation: the role is protected server-side — delete/rename/flag-clear on it return HTTP 409; grants on it stay editable.

**Accepted trade-offs**

- One role now carries *implicit* membership, a small deviation from "membership is always an explicit `user_roles` row". Accepted for the quick-start payoff; contained to exactly one seeded role.
- No general "auto-membership for any role" feature in v1 — only the single seeded `Everyone`. A future dynamic-group feature (rule-based membership) is a separate, larger design, not opened here.
- No default **"admins" access-role** — the `ADMIN`/`END_USER` system-role enum ([F-19](../prd.md)) already carries that axis.

## Follow-ups (traceability per the docs contract)

- Add **R-130** and **R-131** to [arc42 §11](../arc42/11-risks-and-technical-debt.md) (done alongside this ADR).
- Add a Business Rule / use-case for the seeded `Everyone` role to [docs/prd.md](../prd.md) (BR + F-ID) and a Gherkin acceptance scenario ("grant to Everyone → new user reaches it").
- Chapter 8 (crosscutting) authorization concept back-references this ADR when written.

## References

- [ADR-0006](0006-resource-level-acl.md) — RBAC0 model and the ⓐ-all-ports precedent (R-054).
- [F-01b](../prd.md) — admin@local bootstrap seed (the pattern this extends).
- [F-19](../prd.md) — ADMIN/END_USER system-role enum (why no "admins" access-role).
- Prior art: Google Workspace `all-users@`, Microsoft 365 "Everyone" group.
