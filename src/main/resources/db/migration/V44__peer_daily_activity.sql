-- V44: daily activity aggregate for the connection activity heatmap (#32).
--
-- The 30s ActivityPoller already samples `wg show dump` and writes
-- peers.last_seen_at / total_rx_bytes / total_tx_bytes, but none of that
-- carries history — there's no way to see "was this peer connected on
-- day X". Storing one row per raw 30s sample would not scale (peers x
-- ~2880 ticks/day), so instead the poller upserts one row per peer per
-- UTC day here, incrementing sample_hits each time it observes a fresh
-- handshake for that peer on that day. That keeps row count bounded to
-- peers x days regardless of poll frequency.
--
-- Composite (peer_id, day) primary key doubles as the natural upsert key
-- and needs no surrogate id. ON DELETE CASCADE: history for a deleted
-- peer is meaningless without the peer to label it.
--
-- `day` is a plain ISO-8601 string (VARCHAR, not a DATE column): SQLite's
-- JDBC driver only round-trips its native DATE type through a fussy,
-- easily-mismatched string format, and the app runs on both SQLite (dev)
-- and Postgres (prod) per ADR-0004. Fixed-width YYYY-MM-DD sorts and
-- range-compares correctly as a string on both backends, so nothing is
-- lost by not using a real DATE type.
CREATE TABLE peer_daily_activity (
    peer_id     VARCHAR(36) NOT NULL REFERENCES peers(id) ON DELETE CASCADE,
    day         VARCHAR(10) NOT NULL,
    sample_hits INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (peer_id, day)
);

-- The cleanup job prunes by day across all peers; the heatmap query reads
-- by day range too, so an index on the leading (non-PK-leading) column pays
-- off in both directions.
CREATE INDEX ix_peer_daily_activity_day ON peer_daily_activity (day);

-- Retention window in days, mirroring the private_key_retention pattern
-- (a plain settings column, not a separate config table). Default 180
-- matches the value proposed in the issue.
ALTER TABLE settings ADD COLUMN activity_retention_days INTEGER NOT NULL DEFAULT 180;
