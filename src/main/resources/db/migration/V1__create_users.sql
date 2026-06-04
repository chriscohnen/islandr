-- V1: users table
-- Portable SQL only: TEXT for UUIDs (no native UUID type in SQLite), TIMESTAMP for instants.
-- Both SQLite and Postgres accept this verbatim. See docs/adr/0004-sqlite-dev-postgres-prod.md.

CREATE TABLE users (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX ix_users_email ON users (email);
