-- V53: opt-in flag + managed zone for the resource-name DNS resolver (ADR-0023).
--
-- dns_resolver_enabled: off by default on every install (new and existing) —
-- the resolver service (a hand-rolled UDP/TCP listener on wg0) is not built
-- yet, so this migration only persists the admin's intent for that follow-up
-- work, nothing runtime-visible changes by enabling it today.
--
-- dns_resolver_zone: base domain for the managed zone, e.g. "islandr.internal".
-- Null until the admin sets one or enables the resolver (SettingsService
-- defaults it at that point) — never auto-populated by this migration.
ALTER TABLE settings ADD COLUMN dns_resolver_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE settings ADD COLUMN dns_resolver_zone VARCHAR(255);
