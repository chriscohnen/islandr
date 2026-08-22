-- Generic OIDC provider support (issue #69, follow-on to the MS365/Google-only
-- V5 identity schema): admin can configure any OIDC-compliant IdP (Okta,
-- Auth0, Keycloak, ...) in addition to the two hardcoded ones.
--
-- Design choice: a SEPARATE table, not a widened oidc_providers — MS365/Google
-- keep their hardcoded-endpoint fast path (see ProviderEndpoints) completely
-- untouched. A custom provider's endpoints are instead *discovered once*, at
-- config-save time, from {issuer}/.well-known/openid-configuration and cached
-- here — never re-fetched on the login hot path, same "no extra dependency at
-- login time" reasoning ProviderEndpoints already documents for MS365/Google.
--
-- preset distinguishes a templated setup (admin only enters a tenant/org
-- domain, the issuer URL is derived) from a fully generic one where the admin
-- pastes the issuer URL directly (Keycloak, or an unusual Auth0/Okta setup).
-- It only drives which form/logo the admin UI shows — the stored, discovered
-- endpoints are used identically regardless of preset.
CREATE TABLE oidc_custom_providers (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    preset              VARCHAR(16)  NULL
        CHECK (preset IS NULL OR preset IN ('auth0', 'okta')),
    display_name        VARCHAR(255) NOT NULL,
    issuer_url          VARCHAR(512) NOT NULL,
    authorize_endpoint  VARCHAR(512) NULL,
    token_endpoint      VARCHAR(512) NULL,
    jwks_uri            VARCHAR(512) NULL,
    userinfo_endpoint   VARCHAR(512) NULL,
    discovered_issuer   VARCHAR(512) NULL,
    discovered_at       TIMESTAMP    NULL,
    client_id           VARCHAR(255) NULL,
    client_secret       VARCHAR(512) NULL,
    scopes              VARCHAR(255) NOT NULL DEFAULT 'openid profile email',
    allowed_domains     TEXT         NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    updated_by          VARCHAR(255) NOT NULL
);

-- At most one enabled row across oidc_providers + oidc_custom_providers put
-- together (mutual exclusion) is an APPLICATION-level invariant enforced in
-- OidcProviderRegistry, same as it has always been for the two hardcoded
-- providers — never a DB constraint. Two reasons: a partial unique index
-- can't span two tables anyway, and even a single-table one would break an
-- enable/disable swap within one transaction (SQLite enforces UNIQUE
-- immediately per statement, not deferred to commit, and Hibernate's flush
-- order across two dirty rows isn't guaranteed to disable the old one before
-- enabling the new one).

-- ---------------------------------------------------------------------------
-- sessions.provider: widen the CHECK to also accept 'custom'. The *specific*
-- custom provider a 'custom' session came from is looked up by its stored
-- oidc_custom_provider_id, kept only where relevant (a 'custom' session)
-- rather than overloading the provider column with an arbitrary provider id
-- — 'local'/'microsoft'/'google'/'custom' stays a small, self-documenting set.
--
-- SQLite cannot ALTER a CHECK constraint — same limitation V24 hit for
-- resource_ports/port_group_members — so the table is rebuilt. Schema has
-- been stable since V5 (grep confirms no migration since has touched
-- `sessions`), so hand-transcribing it here is safe.
-- ---------------------------------------------------------------------------
CREATE TABLE sessions_v63 (
    id                     VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id                VARCHAR(36) NULL,
    principal              VARCHAR(255) NOT NULL,
    provider               VARCHAR(32) NOT NULL
        CHECK (provider IN ('local', 'microsoft', 'google', 'custom')),
    oidc_custom_provider_id VARCHAR(36) NULL
        REFERENCES oidc_custom_providers(id) ON DELETE SET NULL,
    created_at             TIMESTAMP   NOT NULL,
    expires_at             TIMESTAMP   NOT NULL,
    revoked_at             TIMESTAMP   NULL
);

INSERT INTO sessions_v63 (id, user_id, principal, provider, oidc_custom_provider_id,
                          created_at, expires_at, revoked_at)
    SELECT id, user_id, principal, provider, NULL, created_at, expires_at, revoked_at
    FROM sessions;

DROP TABLE sessions;
ALTER TABLE sessions_v63 RENAME TO sessions;

CREATE INDEX ix_sessions_expires ON sessions (expires_at);
