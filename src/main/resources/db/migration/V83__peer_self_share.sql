-- V83: peer-self-share (loop/TASK.md) — a peer's owner shares one of their
-- own ports with a single named colleague, without touching the ACL matrix.
-- RuleBuilder reads active, unexpired rows as an additional input, the same
-- way it already reads ResourceReservation (#72) — never a RoleResourceGrant.
CREATE TABLE peer_self_shares (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_peer_id  VARCHAR(36) NOT NULL REFERENCES peers(id) ON DELETE CASCADE,
    target_user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    port           INTEGER     NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    valid_until    TIMESTAMP   NOT NULL,
    revoked_at     TIMESTAMP
);

CREATE INDEX ix_peer_self_shares_owner_peer ON peer_self_shares (owner_peer_id);

-- Admin off-switch (default off): this hands a non-admin user a way to open
-- a hole in the firewall, even though it is narrowly scoped (own tunnel
-- address, one named user, port > 1024, always expires) — an operator whose
-- network mixes personal devices and company data should not get that
-- capability without deciding to turn it on.
ALTER TABLE settings ADD COLUMN peer_self_share_enabled INTEGER NOT NULL DEFAULT 0;
