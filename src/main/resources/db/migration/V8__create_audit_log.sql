-- V8: append-only audit log
-- Every mutating action in the system writes one row here (see PRD F-10).
-- 'meta_json' carries a structured before/after diff with sensitive fields
-- (client_secret, private_key_pem) redacted before serialisation.
--
-- Portable SQL: TEXT for the JSON blob (SQLite has no native JSON type;
-- Postgres accepts TEXT-as-JSON fine, queries that need JSON operators can
-- cast at read time).
--
-- No foreign keys on actor or target — actor is a free-form principal
-- string (could be 'admin', an org-user email, or 'system:seed'), and the
-- target row may have been deleted by the time someone reads the log.
-- The log is the record of intent, not a live reference.

CREATE TABLE audit_log (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    actor       VARCHAR(255) NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(255) NULL,
    meta_json   TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL
);

-- Reverse-chronological listing is the dominant query (UI shows latest first,
-- API paginates via 'before' cursor). Compound index keeps id stable as the
-- tie-breaker when two events land in the same millisecond.
CREATE INDEX ix_audit_created ON audit_log (created_at DESC, id);

-- Filter-by-actor and filter-by-action are secondary access patterns
-- (audit views: "what did Alice do?", "show all peer.delete events").
CREATE INDEX ix_audit_actor   ON audit_log (actor);
CREATE INDEX ix_audit_action  ON audit_log (action);
