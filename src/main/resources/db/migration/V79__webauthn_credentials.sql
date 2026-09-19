-- Security keys / passkeys for the local recovery admin (ADR-0028, issue #67).
--
-- Deliberately NOT keyed by the configured admin username. The ENV bootstrap
-- admin has no row in `users` — its identity is the `principal` string — so
-- keying on that name would mean renaming ISLANDR_ADMIN_USER silently orphans
-- every registered authenticator. A rename is a configuration change; it must
-- not be an authentication event. `subject` is therefore a fixed marker for
-- "the local recovery admin", with room for real user ids later.
--
-- Several credentials per subject on purpose: a single one makes handover
-- impossible without a gap in coverage, and a lost key with no second one
-- registered is a lockout.
CREATE TABLE webauthn_credentials (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    subject        VARCHAR(64)  NOT NULL,
    credential_id  VARCHAR(512) NOT NULL,
    -- The relying-party id the credential was registered under, i.e. the host
    -- the browser saw. WebAuthn binds a credential to it: a key enrolled at
    -- hub.islandr.internal is not offered at konsole.firma.de, and the browser
    -- gives no reason. Stored so the console can say so rather than showing a
    -- key that cannot work.
    rp_id          VARCHAR(253) NOT NULL,
    public_key     TEXT         NOT NULL,
    -- Monotonic per credential. An assertion whose counter did not advance is
    -- the signature a cloned authenticator produces, so it is rejected.
    sign_count     BIGINT       NOT NULL DEFAULT 0,
    label          VARCHAR(100),
    created_at     TIMESTAMP    NOT NULL,
    last_used_at   TIMESTAMP
);

CREATE UNIQUE INDEX ix_webauthn_credential_id ON webauthn_credentials (credential_id);
CREATE INDEX ix_webauthn_subject ON webauthn_credentials (subject);
