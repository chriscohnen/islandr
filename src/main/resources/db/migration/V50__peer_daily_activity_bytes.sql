-- V50: per-day rx/tx byte totals on peer_daily_activity, so the connection
-- activity heatmap can color by traffic volume, not just connection
-- presence (sample_hits). The delta-accumulation logic already exists in
-- ActivityPoller for the all-time peers.total_rx_bytes/total_tx_bytes
-- columns (see V44 comment) — this reuses the same delta per poll tick,
-- just also adding it to the current day's row instead of only the
-- all-time total.
ALTER TABLE peer_daily_activity ADD COLUMN rx_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE peer_daily_activity ADD COLUMN tx_bytes BIGINT NOT NULL DEFAULT 0;
