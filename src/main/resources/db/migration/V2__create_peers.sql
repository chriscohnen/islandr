-- V2: peers table
-- Stores the public-facing state of every WireGuard peer.
-- Private keys are NEVER stored — see docs/prd.md F-03 and N-06 (OQ-1 resolution).
-- Activity counters (totalRxBytes/TxBytes, lastSeenAt) get populated by the
-- activity poller later; defaults keep new rows valid.

CREATE TABLE peers (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id            VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name               VARCHAR(255) NOT NULL,
    public_key         VARCHAR(44)  NOT NULL,
    assigned_ip        VARCHAR(45)  NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Populated only when islandr.peer.privateKey.retention=plaintext.
    -- NULL in `never` mode (the default). See docs/adr/0007-private-key-retention.md.
    private_key_pem    VARCHAR(44)  NULL,
    last_seen_at       TIMESTAMP    NULL,
    last_seen_endpoint VARCHAR(255) NULL,
    total_rx_bytes     BIGINT       NOT NULL DEFAULT 0,
    total_tx_bytes     BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX ix_peers_public_key ON peers (public_key);
CREATE UNIQUE INDEX ix_peers_assigned_ip ON peers (assigned_ip);
CREATE INDEX ix_peers_user_id ON peers (user_id);
