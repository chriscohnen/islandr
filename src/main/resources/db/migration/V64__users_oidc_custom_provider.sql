-- Plain, portable column add (no CHECK/constraint touched here) — safe on
-- both SQLite and PostgreSQL via a simple ALTER TABLE ADD COLUMN.
-- The oidc_provider CHECK widening to allow 'custom' happens separately in
-- V65 (Java migration — SQLite can't ALTER a CHECK, needs a table rebuild;
-- see V37 for the established pattern of reading the live DDL rather than
-- hand-transcribing the whole users schema).
ALTER TABLE users ADD COLUMN oidc_custom_provider_id VARCHAR(36) NULL
    REFERENCES oidc_custom_providers(id) ON DELETE SET NULL;
