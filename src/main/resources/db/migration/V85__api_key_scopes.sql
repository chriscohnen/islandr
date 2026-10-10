-- V85: apikey-scopes (loop/TASK.md) — an API key held full admin-equivalent
-- power in v1 (ADR-0026, R-184) with no per-key scoping at all. This gives
-- each key a set of named capability scopes instead.
--
-- site_id is unused in 1.1.0 (every row has it NULL) but included now on
-- purpose: two scopes deferred to 1.2.0 (rules:read, telemetry:write) need a
-- scope bound to a single site rather than the whole install, and adding
-- that column later would be a second migration plus a backfill. Included
-- in the uniqueness constraint so the same scope can be granted for more
-- than one site once that lands.
CREATE TABLE api_key_scopes (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    api_key_id VARCHAR(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    scope      VARCHAR(50) NOT NULL,
    site_id    VARCHAR(36) REFERENCES sites(id) ON DELETE CASCADE,
    UNIQUE (api_key_id, scope, site_id)
);

CREATE INDEX ix_api_key_scopes_api_key ON api_key_scopes (api_key_id);

-- Every API key that exists before this migration was created under the old
-- full-admin-equivalent model — grandfather it in as "full" rather than
-- silently locking it down to nothing on upgrade, which would break
-- whatever already depends on it (the companion app, a script) without
-- warning. New keys choose a scope at creation time from here on.
INSERT INTO api_key_scopes (id, api_key_id, scope, site_id)
SELECT lower(hex(randomblob(16))), id, 'full', NULL FROM api_keys;
