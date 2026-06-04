# ADR-0008 — Runtime settings live in the database, not in application.properties

**Status:** Accepted
**Date:** 2026-05-30
**Deciders:** Christian Cohnen

## Context

While wiring up the first peer-create endpoint, six configuration values landed in `application.properties`:

```
islandr.wg.subnet=10.8.0.0/24
islandr.wg.server.publicKey=PLACEHOLDER...
islandr.wg.server.endpoint=vpn.example.com:51820
islandr.wg.client.allowedIps=10.8.0.0/24
islandr.wg.client.dns=
islandr.peer.privateKey.retention=never
```

All six are values the admin needs to change through the UI — the WG server public key changes when the hub is rekeyed, AllowedIPs grows when new sites are added, retention mode is a runtime policy decision. Treating them as Quarkus config properties means:

- The admin cannot change them without SSH access to the hub VM and a service restart.
- Audit trails ("who changed retention to plaintext, when?") have no place to live.
- The Admin Console "Einstellungen" screen would either have to be a read-only view of a YAML file, or duplicate the values into the DB anyway.

The same problem will compound as more knobs appear: nftables ruleset reload throttle, activity-poll interval, peer-disable-on-stale window, email/Slack notification settings.

Two camps for handling this:

- **Camp A: config-as-code** — everything in `application.properties` / env vars, no UI editing, "infrastructure-as-code" mindset. Operators edit the file, restart the service.
- **Camp B: runtime config in DB** — bootstrap-only values (datasource URL, port, wg interface name) in properties; everything else in a `settings` table with a UI editor.

Camp A is what Quarkus-default tutorials show. It's right for *deployment-shape* config: which port, which database. It's wrong for *operational-state* config: what's the VPN endpoint URL, what's the retention policy. The product we're building is a self-hosted admin tool; the admin lives in the UI, not in the file system.

## Decision

**Split config into two homes:**

| Lives in `application.properties` / env | Lives in DB `settings` table (admin-editable via UI) |
|---|---|
| `quarkus.datasource.*` (DB connection) | `wg.subnet` |
| `quarkus.http.port` | `wg.server.publicKey` |
| `islandr.wg.mode` (mock / real) | `wg.server.endpoint` |
| `islandr.wg.interface` (wg0) | `wg.client.allowedIps` |
| Flyway / Hibernate plumbing | `wg.client.dns` |
| | `peer.privateKey.retention` (never / plaintext) |

**Rule of thumb:** if changing the value would require a service restart anyway (DB connection), it stays in properties. If it's a policy or topology decision the admin should be able to flip from the UI, it goes in the DB.

**Schema: a singleton settings table.**

```sql
CREATE TABLE settings (
    id                       INTEGER      NOT NULL PRIMARY KEY CHECK (id = 1),
    wg_subnet                VARCHAR(50)  NOT NULL,
    wg_server_public_key     VARCHAR(44)  NOT NULL,
    wg_server_endpoint       VARCHAR(255) NOT NULL,
    wg_client_allowed_ips    TEXT         NOT NULL,
    wg_client_dns            VARCHAR(255) NULL,
    private_key_retention    VARCHAR(20)  NOT NULL DEFAULT 'never',
    updated_at               TIMESTAMP    NOT NULL,
    updated_by               VARCHAR(255) NOT NULL
);
```

The `CHECK (id = 1)` enforces exactly one row. `INSERT ON CONFLICT (id) DO UPDATE` (or the equivalent Hibernate save) is the only write path. No "which row is current?" question.

**Flyway seeds defaults.** First-run migration inserts the single row with placeholders (`PLACEHOLDER_SERVER_PUBKEY...`) so the app boots cleanly. The Admin Console shows a permanent "Setup unvollständig" banner until the placeholders are replaced.

**Access pattern.** A `SettingsService` (`@ApplicationScoped`) reads the row on demand. No caching in v1 — SQLite hits are sub-millisecond and the read frequency is low (peer create, peer reshow, nftables recompute). Add a cache invalidated on `PUT /settings` if profiling shows a hot path.

## Alternatives considered (Pugh Matrix)

Baseline: **Singleton settings table + bootstrap properties** (the decision).

| Criterion (weight)                              | Singleton table (baseline) | All in application.properties | EAV (key/value table) | Per-domain tables (wg_settings, peer_settings) |
|-------------------------------------------------|----------------------------|-------------------------------|-----------------------|-------------------------------------------------|
| Admin can change without restart (5)            | 0                          | -1                            | 0                     | 0                                               |
| Type safety / schema validation (4)             | 0                          | 0                             | -1                    | 0                                               |
| Audit trail of changes (4)                      | 0                          | -1                            | 0                     | 0                                               |
| UI editor complexity (3)                        | 0                          | -1                            | -1                    | -1                                              |
| Race-condition resistance (CHECK id=1) (3)      | 0                          | 0                             | -1                    | 0                                               |
| Migration cost when adding a new setting (2)    | 0                          | +1                            | +1                    | 0                                               |
| Lines of code (2)                               | 0                          | +1                            | 0                     | -1                                              |
| **Weighted total**                              | **0**                      | **−6**                        | **−5**                | **−3**                                          |

Honest reading:

- **All in `application.properties`** scores well on "lines of code" and "easy to add" because there's no schema. Loses hard on the criteria that motivated this ADR. Reject.
- **EAV (`settings(key, value)`)** trades a `VARCHAR` column for schema clarity. Every read becomes `WHERE key = '…'`, every write a `VARCHAR` interpretation. Loses on type safety (everything's a string), on race-resistance (no PK constraint on the *set* of settings), and on UI complexity (the editor has to know per-key types out-of-band). Common antipattern. Reject.
- **Per-domain tables** (separate `wg_settings`, `peer_settings`, etc.) sounds tidy but creates the "who owns the saving" question — `PUT /settings` becomes N queries in a transaction, the UI has to reason about multiple endpoints. Loses on UI complexity and LOC for a v1-sized config. Defer until we have ≥20 settings spread across truly independent domains.
- **+1 on "migration cost when adding a setting"** for properties and EAV is real: a new singleton-table column needs a Flyway migration. We accept that — it's a one-line `ALTER TABLE settings ADD COLUMN` and forces a moment of "should this really be in settings or properties." That friction is a feature.

## Consequences

**Positive**

- Admin changes happen in the UI with audit (`who, when, what changed`). No SSH.
- Schema validates the shape — you cannot store `wg_server_public_key = "totally not a base64 key"` if the column has a length constraint and the service validates.
- One row, one transaction, no consistency-during-update problem.
- Migration path obvious: `ALTER TABLE settings ADD COLUMN foo` + new entity field + new DTO field + new UI input.

**Risks created**

- **R-070** — Bootstrap chicken-and-egg: on first run, the DB is empty until Flyway runs. Mitigation: Flyway seeds the row in the same migration that creates the table. App always sees a populated row.
- **R-071** — A setting change that affects running state (retention mode switch never→plaintext leaves old peers with NULL key column; switch plaintext→never leaves old keys lingering in the DB) needs explicit handling. Mitigation: the `SettingsService.update()` method runs side-effects per changed field — for retention switch this means warning the admin in the UI before the change, and offering a `purge-private-keys` action after.
- **R-072** — Reading from DB on every nftables recompute could become a hot path if the recompute becomes per-request frequent. Mitigation: SQLite local read is sub-millisecond, not measurable; add an in-memory cache invalidated on `PUT /settings` if profiling proves it's needed.
- **R-073** — Two configuration homes (properties + DB) is harder to document than one. Mitigation: a dedicated "Configuration reference" doc page lists every setting and where it lives, with a clear "deployment-shape" vs. "operational-state" framing.
- **R-074** — `wg.mode` (mock/real) deliberately stays in properties even though "could be runtime-toggled" was discussed. Mitigation: switching from mock to real at runtime would silently change every peer's reachability — we don't want that to be possible through a UI checkbox. Restart-required is the right friction here.

**Accepted trade-offs**

- A new setting requires a code change *and* a migration. We choose that over "just edit application.properties" for the schema/UI/audit gains.
- The setting screen is one large form, not many small ones, because the settings live in one row. If the form grows past 20 fields we'll re-evaluate.
- No environment-variable override for DB-stored settings. Operators who want config-as-code (Ansible, NixOS modules) write a one-shot SQL bootstrap or POST to `/api/v1/settings` from their deploy script.

## References

- [docs/prd.md §9](../prd.md#9-rest-api-v1-shape-only) — adds `GET /api/v1/settings` and `PUT /api/v1/settings`
- [docs/adr/0007-private-key-retention.md](0007-private-key-retention.md) — the `private_key_retention` setting is moved from properties to DB by this ADR
- [Twelve-Factor App §3 "Config in env"](https://12factor.net/config) — the rule this ADR partially rejects, on the basis that "config" there means *deployment* config (DB URL, port), which we keep in env; runtime operator policy is a different category that 12-Factor doesn't address well
