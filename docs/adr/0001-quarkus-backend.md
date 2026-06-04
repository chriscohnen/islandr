# ADR-0001 — Quarkus as the backend framework

**Status:** Accepted
**Date:** 2026-05-30
**Deciders:** Christian Cohnen

## Context

Islandr's backend needs to:

- Expose a REST/JSON API for the Admin Console and Self-Service Portal.
- Shell out to `wg`, `ip`, and `nft` CLIs on the hub VM (long-lived process, file descriptors, real-time exit codes).
- Poll `wg show <iface> dump` every 30s and persist samples.
- Ship as a single binary so the hub VM does not need a JVM installed.
- Iterate fast in development — peer management, ACL resolution, and nftables generation are inherently trial-and-error work.

The Rust path was considered seriously and dropped. Rust would give us a smaller binary and lower idle memory, but the iteration cycle (slow rebuilds, more friction around ORM and HTTP boilerplate, smaller talent pool on the team) cost more than the runtime savings were worth for a single-VM self-hosted tool.

The team is JVM-fluent. The artifact must run with no JVM installed on the target VM, so a native-image story is required.

## Decision

Use **Quarkus 3.x on Java 21** as the backend framework.

- REST endpoints via Quarkus REST (formerly RESTEasy Reactive).
- ORM via Hibernate + Panache.
- Process invocation via plain `java.lang.ProcessBuilder` for `wg` and `nft` — no special framework abstraction needed.
- Scheduling for the activity poller via `@Scheduled` (Quarkus Scheduler).
- Production binary built as a Quarkus **native image** via GraalVM Mandrel.
- Dev loop uses Quarkus Live Coding (`quarkus dev`) and Dev Services for the database.

## Alternatives considered (Pugh Matrix)

Scoring vs. **Quarkus** as the baseline. `+1` better, `0` equal, `-1` worse, `?` not enough information.

| Criterion (weight)            | Quarkus (baseline) | Spring Boot | Rust (Axum + sqlx) | Go (chi + sqlc) |
|-------------------------------|--------------------|-------------|---------------------|-----------------|
| Iteration speed (5)           | 0                  | -1          | -1                  | 0               |
| Single-binary deployment (4)  | 0                  | -1          | +1                  | +1              |
| Idle memory footprint (3)     | 0                  | -1          | +1                  | +1              |
| Team familiarity (4)          | 0                  | +1          | -1                  | -1              |
| ORM / migration ergonomics (3)| 0                  | 0           | -1                  | -1              |
| Native-image maturity (3)     | 0                  | -1          | n/a (native)        | n/a (native)    |
| Process-invocation ergonomics (2) | 0              | 0           | 0                   | 0               |
| **Weighted total**            | **0**              | **−9**      | **−2**              | **−1**          |

The matrix says Quarkus wins by a clear margin against Spring Boot and a thin margin against Go/Rust. The thin margins are real, not noise: Go and Rust both give better idle footprint and single-binary stories, but lose enough on iteration speed and ORM ergonomics that for a single-VM, single-team product, the trade goes the other way.

Notes on individual cells:

- **Spring Boot, iteration speed** — DevTools is fine but not as tight as Quarkus Live Coding for this kind of CRUD+CLI work.
- **Spring Boot, native** — Spring Native exists but is less mature than Quarkus's; native build times and missing-reflection-config issues are still common.
- **Rust, team familiarity** — possible but real cost for a small team. Counts against it.
- **Rust, ORM ergonomics** — `sqlx` and `sea-orm` are good but migration tooling and the Panache `find().list()` style of ad-hoc query are missing.
- **Go, ORM ergonomics** — `sqlc` is excellent but more boilerplate than Panache for the simple CRUD that makes up most endpoints here.

## Consequences

**Positive**

- Fast dev loop via Live Coding closes the gap to scripting languages on iteration speed.
- Native binary meets N-03 (no JVM install on hub VM).
- Panache + Hibernate handle the SQLite dev / Postgres prod split (see [ADR-0004](0004-sqlite-dev-postgres-prod.md)) without code changes.
- `@Scheduled` covers the activity poller with no extra dependency.

**Risks created**

- **R-001** (to be tracked in arc42 Chapter 11) — Native-image build adds CI time (~3–5 min per build) and a class of "works on JVM, breaks on native" reflection/serialization bugs. Mitigation: run integration tests against the native binary, not only the JVM build.
- **R-002** — Native image with GraalVM has a higher peak build memory requirement (~6–8 GB). Mitigation: build on a machine with ≥8 GB RAM or use a release-only CI job rather than building natively on every PR.
- **R-003** — Long-running `ProcessBuilder` calls for `nft -f` can block a worker thread. Mitigation: run them on Quarkus's worker pool, not the I/O thread; bound execution time.

**Accepted trade-offs**

- Native binary is ~50 MB compared to ~10–15 MB for Rust/Go. Acceptable for a hub-VM-side tool.
- Idle memory is ~80–120 MB native vs. ~10–30 MB for Rust/Go. Acceptable.
- Dev-time JVM startup is fine because `quarkus dev` keeps the process warm.

## References

- [Quarkus Live Coding](https://quarkus.io/guides/getting-started#development-mode)
- [Quarkus native image guide](https://quarkus.io/guides/building-native-image)
- [docs/prd.md](../prd.md) — non-functional requirements N-02, N-03
