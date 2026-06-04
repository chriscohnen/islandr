-- V11: firewall state singleton
-- One row that records the last successful (or attempted) nftables apply.
-- The ruleset is fully recomputed from DB on every change (see ADR-0003 §F-08);
-- this table is the audit trail of what the kernel got told to do, plus the
-- preview text the UI shows on /firewall.
--
-- 'ok' / 'failed' (status) — failed rows also carry stderr_text so the UI can
-- show the nft validation error. On a successful apply, the next ok-row
-- overwrites the previous failed status.

CREATE TABLE firewall_state (
    id              INTEGER      NOT NULL PRIMARY KEY CHECK (id = 1),
    -- last apply attempt
    last_status     VARCHAR(16)  NOT NULL CHECK (last_status IN ('ok', 'failed', 'never')),
    last_attempt_at TIMESTAMP    NULL,
    last_ok_at      TIMESTAMP    NULL,
    rule_count      INTEGER      NOT NULL DEFAULT 0,
    -- the actual ruleset string that was applied (or attempted). nullable
    -- because the seed row has nothing yet; capped to 1 MB by app logic.
    ruleset_text    TEXT         NULL,
    -- nft stderr from the last failed apply; cleared on the next ok-apply.
    stderr_text     TEXT         NULL
);

INSERT INTO firewall_state (id, last_status, rule_count) VALUES (1, 'never', 0);
