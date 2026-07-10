-- Add the auto_all flag to roles (ADR-0013). An auto_all role includes every
-- user implicitly — present and future — without user_roles rows; exactly one
-- seeded role (Everyone) sets it. INTEGER for SQLite/Postgres portability.
ALTER TABLE roles ADD COLUMN auto_all INTEGER NOT NULL DEFAULT 0;
