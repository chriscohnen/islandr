-- V3: settings singleton table
-- Exactly one row (CHECK id = 1). Operator edits via the Admin Console.
-- See docs/adr/0008-runtime-settings-in-db.md for rationale.
-- Bootstrap-only config (datasource URL, port, wg interface) stays in
-- application.properties — those need to be known before the DB is up.

CREATE TABLE settings (
    id                       INTEGER      NOT NULL PRIMARY KEY CHECK (id = 1),
    wg_subnet                VARCHAR(50)  NOT NULL,
    wg_server_public_key     VARCHAR(44)  NOT NULL,
    wg_server_endpoint       VARCHAR(255) NOT NULL,
    wg_client_allowed_ips    TEXT         NOT NULL,
    wg_client_dns            VARCHAR(255) NULL,
    private_key_retention    VARCHAR(20)  NOT NULL DEFAULT 'never',
    updated_at               TIMESTAMP    NOT NULL,
    updated_by               VARCHAR(255) NOT NULL
);

-- Seed defaults so the app boots on a fresh DB without an out-of-band SQL step.
-- The PLACEHOLDER values force the admin to visit the settings screen before
-- the system is usable (the UI surfaces this as a "Setup unvollständig" banner).
INSERT INTO settings (
    id, wg_subnet, wg_server_public_key, wg_server_endpoint,
    wg_client_allowed_ips, wg_client_dns, private_key_retention,
    updated_at, updated_by
) VALUES (
    1,
    '10.8.0.0/24',
    'PLACEHOLDER_SERVER_PUBKEY_REPLACE_BEFORE_PROD=',
    'vpn.example.com:51820',
    '10.8.0.0/24',
    NULL,
    'never',
    CURRENT_TIMESTAMP,
    'system:seed'
);
