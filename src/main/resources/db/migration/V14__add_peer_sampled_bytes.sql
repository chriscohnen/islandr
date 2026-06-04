-- V14: per-peer sampled byte counters for the activity poller.
--
-- wg counters reset to 0 on interface restart, so we cannot accumulate
-- them directly into total_rx/tx_bytes. Instead the poller keeps the
-- last raw value it read from `wg show dump` here, and computes a delta
-- on every sample. Only positive deltas (counter advanced) are added to
-- the totals; a negative delta (= counter reset) is treated as 0 so the
-- totals are monotonically increasing regardless of restarts.
ALTER TABLE peers ADD COLUMN last_sampled_rx_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE peers ADD COLUMN last_sampled_tx_bytes BIGINT NOT NULL DEFAULT 0;
