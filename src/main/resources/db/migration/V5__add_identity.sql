-- V5: Identity (OIDC providers, sessions, user OIDC linkage + avatar cache, Gravatar toggle)
-- Portable SQL only — works on SQLite and Postgres without dialect tricks.
-- See:
--   ADR-0008 (runtime settings in DB) — oidc_providers follows the same singleton-per-key pattern
--   docs/PRD §Identity — MS365 + Google + ENV-Admin, GUI-only configuration

-- ---------------------------------------------------------------------------
-- OIDC provider configuration
-- One row per provider (key = 'microsoft' | 'google'). Both rows are seeded
-- disabled, so the system stays in local-admin-only mode until an admin
-- enables a provider through the GUI. tenant_id is only meaningful for
-- Microsoft (single-tenant requirement, see CLAUDE.md). allowed_domains is
-- a CSV of email-domain allowlists for auto-provisioning on first login.
-- client_secret is stored as plaintext deliberately — same trust boundary
-- as the host root account; encrypting it at-rest would only move the
-- secret-of-secrets problem one level deeper without a real key store.
-- ---------------------------------------------------------------------------
CREATE TABLE oidc_providers (
    provider_key    VARCHAR(32)  NOT NULL PRIMARY KEY
        CHECK (provider_key IN ('microsoft', 'google')),
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    client_id       VARCHAR(255) NULL,
    client_secret   VARCHAR(512) NULL,
    tenant_id       VARCHAR(255) NULL,
    allowed_domains TEXT         NULL,
    updated_at      TIMESTAMP    NOT NULL,
    updated_by      VARCHAR(255) NOT NULL
);

INSERT INTO oidc_providers (provider_key, enabled, updated_at, updated_by)
VALUES ('microsoft', FALSE, CURRENT_TIMESTAMP, 'system:seed');
INSERT INTO oidc_providers (provider_key, enabled, updated_at, updated_by)
VALUES ('google', FALSE, CURRENT_TIMESTAMP, 'system:seed');

-- ---------------------------------------------------------------------------
-- Sessions — server-side revocable. The id is a random 32-byte base64url
-- string set as an HttpOnly cookie. Local-admin sessions store user_id = NULL
-- (the ENV-bootstrap admin has no users row); the principal name is then
-- the literal 'admin'.
-- ---------------------------------------------------------------------------
CREATE TABLE sessions (
    id           VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id      VARCHAR(36) NULL,
    principal    VARCHAR(255) NOT NULL,
    provider     VARCHAR(32) NOT NULL
        CHECK (provider IN ('local', 'microsoft', 'google')),
    created_at   TIMESTAMP   NOT NULL,
    expires_at   TIMESTAMP   NOT NULL,
    revoked_at   TIMESTAMP   NULL
);
CREATE INDEX ix_sessions_expires ON sessions (expires_at);

-- ---------------------------------------------------------------------------
-- User OIDC linkage + avatar cache
-- oidc_subject is the stable provider-issued user id (MS Object ID / Google sub).
-- The unique index uses a partial WHERE so multiple local users (oidc_provider IS NULL)
-- can coexist without collision. avatar_bytes caches MS Graph / Google picture / Gravatar
-- responses; null means "no avatar known, frontend renders initials".
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN oidc_provider VARCHAR(32) NULL
    CHECK (oidc_provider IS NULL OR oidc_provider IN ('microsoft', 'google'));
ALTER TABLE users ADD COLUMN oidc_subject VARCHAR(255) NULL;
ALTER TABLE users ADD COLUMN avatar_bytes BLOB NULL;
ALTER TABLE users ADD COLUMN avatar_content_type VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN avatar_etag VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN avatar_fetched_at TIMESTAMP NULL;

CREATE UNIQUE INDEX ix_users_oidc ON users (oidc_provider, oidc_subject)
    WHERE oidc_provider IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Gravatar toggle — additive column on settings. Defaults FALSE (privacy-first:
-- enabling sends an email hash to a third party).
-- ---------------------------------------------------------------------------
ALTER TABLE settings ADD COLUMN gravatar_enabled BOOLEAN NOT NULL DEFAULT FALSE;
