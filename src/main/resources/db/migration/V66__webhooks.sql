-- Outgoing webhooks for events (issue #68). One row per admin-configured
-- webhook target: a URL, a secret for HMAC-signing deliveries, and a CSV of
-- which canonical event types (see WebhookEventType) this webhook wants —
-- the per-webhook filter is the whole point, not "every event to every URL".
CREATE TABLE webhooks (
    id                    VARCHAR(36)   NOT NULL PRIMARY KEY,
    url                   VARCHAR(1024) NOT NULL,
    description           VARCHAR(255),
    -- HMAC-SHA256 signing secret, generated server-side, shown to the admin
    -- only once (creation/rotation response) — same trust-boundary rationale
    -- as OidcProvider.clientSecret: unencrypted at rest, host root already
    -- has DB access.
    secret                VARCHAR(128)  NOT NULL,
    -- CSV of WebhookEventType keys, e.g. "peer.connected,acl.grant_created".
    event_types           TEXT          NOT NULL,
    enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
    last_delivery_at      TIMESTAMP,
    last_delivery_status  VARCHAR(16),  -- 'ok' | 'failed' | NULL (never delivered)
    last_delivery_error   TEXT,
    created_at            TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP     NOT NULL,
    updated_by            VARCHAR(255)  NOT NULL
);
