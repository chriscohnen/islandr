-- Opt-out toggle for the external automation API facade (issue #15,
-- ADR-0026). Default TRUE — the facade is already API-key-gated; this is a
-- further, explicit hardening switch for operators who never intend to use
-- it, not something existing installs need to opt into.
ALTER TABLE settings ADD COLUMN external_api_enabled INTEGER NOT NULL DEFAULT 1;
