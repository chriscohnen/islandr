-- API keys for the external automation API (issue #15, ADR-0026). Only the
-- SHA-256 hash of the raw key is ever stored — the raw value is shown to the
-- admin exactly once, at creation, same one-time-secret posture as a peer's
-- private key or a webhook's HMAC secret. key_prefix is a short, non-secret
-- slice of the raw key kept in plaintext purely so the admin can tell keys
-- apart in the list without re-exposing the full value.
CREATE TABLE api_keys (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    label          VARCHAR(255) NOT NULL,
    key_hash       VARCHAR(128) NOT NULL,
    key_prefix     VARCHAR(24)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    last_used_at   TIMESTAMP,
    revoked_at     TIMESTAMP
);

CREATE UNIQUE INDEX ix_api_keys_hash ON api_keys (key_hash);
