# ADR-0004 — SQLite for dev, PostgreSQL for prod

**Status:** Accepted
**Date:** 2026-05-30
**Deciders:** Christian Cohnen

## Context

Islandr stores users, groups, networks, peers, ACL membership, activity samples, and an audit log. Working set size is small (≤ low thousands of peers per hub, a few million activity samples at 30s polling with 30-day retention). Concurrency is light — one admin or a handful, a poller writing samples on a fixed cadence, end-users reading their own data.

Three tensions:

- **Dev setup friction.** A self-hosted single-binary tool should not require a developer to install Postgres before running tests. `quarkus dev` plus a file-backed SQLite gives "clone, run, code" in seconds.
- **Small-self-hoster production.** A one-admin homelab or a small company with ≤200 peers does not want to run a separate Postgres process. They want the same single-binary story they had in dev, just pointed at a durable file. SQLite is fine for that working set; the only Postgres they'd run *because of Islandr* is operational overhead they don't want.
- **Team-self-hoster production.** A larger operator with existing Postgres infrastructure expects to point Islandr at it via a connection string and to back it up, monitor it, and replicate it the way they already do for everything else.

A single-DB choice means picking one of:

- Only SQLite — easy install for everyone, but uncomfortable for operators who'd rather centralize on Postgres they already run.
- Only Postgres — pushes setup friction onto every dev and every solo self-hoster.

Both are worse than letting the deployment pick.

The interesting sub-question raised after the first draft of this ADR: **is SQLite actually the right embedded option, or is there a more Quarkus-native one?** That question is answered in the Alternatives section below — the short version is that the other "embedded Java DBs" (H2, HSQLDB) are not safe for production use according to their own maintainers, and "embedded Postgres" isn't really embedded.

## Decision

Support **both** with the same code path:

- **Dev and "small self-hoster"**: SQLite, file at `./data/islandr.db` (or `/var/lib/islandr/islandr.db` in production).
- **"Team / company self-hoster"**: PostgreSQL 14+.

Implementation:

- Hibernate ORM via Quarkus Panache. Use only the SQL features both dialects support — no Postgres-only types, no SQLite-only `WITHOUT ROWID` tricks.
- Database migrations via Flyway with one set of migration scripts that work on both dialects. Where they can't (rare), Flyway's per-dialect script folders carry the divergence.
- The Quarkus DataSource config is driven by `quarkus.datasource.db-kind` at runtime — same binary, set `sqlite` or `postgresql`. JDBC URL via env var.
- Integration tests run on both backends in CI (one job each).
- Activity samples (the table that grows) get an index on `(peer_id, sampled_at)` and a periodic deletion job to enforce retention. Both DBs handle this fine at the scale we're at.

## Alternatives considered (Pugh Matrix)

Baseline: **SQLite + PostgreSQL, single code path** (the decision).

Two questions get scored separately:

1. **Cross-deployment shape** — one DB everywhere vs. split dev/prod vs. supporting two backends. Columns 2–4.
2. **Which embedded DB** (if we want one) — SQLite vs. the Java-native embedded options vs. "embedded Postgres". Columns 5–7.

| Criterion (weight)                            | SQLite + Postgres (baseline) | SQLite only | Postgres only | H2 dev + Postgres prod | H2 (file mode) instead of SQLite | HSQLDB instead of SQLite | Embedded Postgres (zonky) instead of SQLite |
|-----------------------------------------------|------------------------------|-------------|---------------|------------------------|-----------------------------------|---------------------------|----------------------------------------------|
| Dev setup friction (4)                        | 0                            | +1          | -1            | 0                      | 0                                 | 0                         | -1                                           |
| Solo / small self-host fit (3)                | 0                            | +1          | -1            | -1                     | -1                                | -1                        | -1                                           |
| Team self-host fit (4)                        | 0                            | -1          | +1            | 0                      | 0                                 | 0                         | 0                                            |
| Operator backup/monitoring story (3)          | 0                            | -1          | +1            | 0                      | -1                                | -1                        | 0                                            |
| One code path (4)                             | 0                            | +1          | +1            | -1                     | 0                                 | 0                         | 0                                            |
| Activity-samples write rate (2)               | 0                            | 0           | +1            | 0                      | 0                                 | 0                         | +1                                           |
| Test parity dev↔prod (3)                      | 0                            | n/a         | n/a           | -1                     | 0                                 | 0                         | +1                                           |
| Production durability reputation (5)          | 0                            | 0           | 0             | 0                      | -1                                | -1                        | 0                                            |
| Quarkus extension / native-image friction (3) | 0                            | 0           | 0             | 0                      | +1                                | +1                        | -1                                           |
| Single-binary deployment fit ([ADR-0001](0001-quarkus-backend.md)) (3) | 0   | 0           | 0             | 0                      | 0                                 | 0                         | -1                                           |
| **Weighted total**                            | **0**                        | **−2**      | **+3**        | **−10**                | **−7**                            | **−7**                    | **−5**                                       |

Honest reading:

- **Postgres only** scores +3. The reason we don't pick it: the +3 comes mostly from production-side criteria. The dev-setup-friction hit (−4) is real and hits every contributor and every solo self-hoster on every machine setup. The decision deliberately trades a slightly worse Pugh score for lower friction.
- **SQLite only** is fine for v1 if we're honest about the target. Loses on team self-host fit — and we have explicit interest in team self-hosters per the PRD.
- **H2 dev + Postgres prod** is the bad-old-Spring-era pattern: H2 lies about SQL semantics in just enough ways to ship bugs to production. Loses hard on test parity.
- **H2 in file mode as the production embedded DB** would be the most Quarkus-comfortable choice — it's a first-class Quarkus extension (`quarkus-jdbc-h2`), it's pure Java, and it works in native image without reflection-config gymnastics. We reject it on a single criterion that dominates: **the H2 maintainers themselves do not recommend it for production**, and the project has a long-documented history of corruption after unclean shutdowns. That −5 on "Production durability reputation" is the whole story.
- **HSQLDB** is the same call as H2, slightly worse on every axis. Same rejection.
- **Embedded Postgres (zonky.io)** is **not actually embedded** — it ships per-platform Postgres binaries (~70 MB) that get unpacked to a temp dir at startup and run as a real `postgres` subprocess. That breaks the single-binary deployment story ([ADR-0001](0001-quarkus-backend.md)) and breaks native image. It's a fine *integration-test* tool. It is not a production-embedded answer.

### Why SQLite specifically, given the Quarkus-extension friction

SQLite has no official Quarkus extension; we pull in `org.xerial:sqlite-jdbc` directly and configure an Agroal datasource manually. Native image needs a small piece of reflection config (xerial ships metadata for it but it isn't always seamless). That is real friction.

But the alternatives that *don't* have that friction either:

- ship a database engine the maintainers say not to use in production (H2, HSQLDB), or
- aren't actually embedded (zonky), or
- aren't a SQL database at all (DuckDB is analytics, MVStore is the wrong abstraction layer).

We accept the small Quarkus-side friction in exchange for SQLite's production reputation: the most-deployed, most-tested database engine in the world, formal-methods-tested file format, ~1 MB footprint, one-file-equals-one-database backup story. That's a trade worth making.

The baseline wins on the strength of "one code path, two deployments" — supporting both is cheaper than people think if you discipline yourself to portable SQL from day one, and the embedded option is genuinely production-safe rather than a footgun the operator will eventually trigger.

## Consequences

**Positive**

- `git clone && ./mvnw quarkus:dev` works with no DB install.
- Operators with existing Postgres infrastructure point Islandr at it via env var. No code change.
- CI tests both dialects, catching portability bugs early.

**Risks created**

- **R-030** — "Portable SQL only" is a constraint that erodes over time. A future developer adds a Postgres `JSONB` column or a SQLite `CHECK` constraint and breaks the other backend. Mitigation: keep both CI jobs green; the rule is in the contributor guide.
- **R-031** — SQLite is a single file. An operator who treats it as durable for a team-sized deployment will be unhappy when the disk fails. Mitigation: deployment guide states clearly that team self-hosters should use Postgres; the README doesn't bury this.
- **R-032** — Migrations that pass on SQLite may fail on Postgres due to subtle dialect differences (default value syntax, identifier casing). Mitigation: Flyway runs in CI on both; migrations are tested, not assumed.
- **R-033** — The activity-samples table is the only place we're near a write-rate concern. 30s polling × N peers × 30 days × indexes is fine on Postgres up to thousands of peers; SQLite handles it up to mid-hundreds before write contention bites. Mitigation: documented limit; operators near the limit migrate to Postgres.
- **R-034** — SQLite is not an official Quarkus extension. We bring in `org.xerial:sqlite-jdbc` directly and configure a datasource by hand. Native image needs reflection config (xerial provides metadata; it isn't always seamless). Mitigation: validate the native build with SQLite at the first backend spike (PRD OQ-2 territory); if it turns out to be a sustained drag, the comparison in this ADR shifts and either Postgres-only or a documented hybrid (file-mode SQLite on JVM, Postgres on native) gets considered. Tracked as the first thing to test when the Quarkus project gets stood up.

**Accepted trade-offs**

- No Postgres-specific features (JSONB columns, full-text search, `LISTEN`/`NOTIFY`). If we genuinely need them later, this ADR gets superseded.
- Dialect-specific tuning is forgone in exchange for the one-code-path simplicity.

## References

- [Quarkus DataSource configuration](https://quarkus.io/guides/datasource)
- [xerial sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) — the JDBC driver we'll use; documents native-image support
- [SQLite — Appropriate Uses For SQLite](https://www.sqlite.org/whentouse.html) — covers exactly the "should I use this in production?" question, by the SQLite team
- [H2 caveats](https://www.h2database.com/html/advanced.html#durability_problems) — the H2 team's own writeup on durability, the basis for the production-reputation score
- [Flyway with multi-dialect scripts](https://documentation.red-gate.com/fd/database-specific-support-184127425.html)
- [docs/prd.md](../prd.md) — N-01 (self-hostable), domain model in §7
- [docs/adr/0001-quarkus-backend.md](0001-quarkus-backend.md) — single-binary / native image constraint that ruled out "embedded Postgres"
