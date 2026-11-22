-- edge-plan-peers: the plan an edge fetches carries a serial that rises whenever
-- its content changes, and stays put while it does not. The hub keeps the serial
-- and a hash of the last plan content it handed out; a fetch compares and bumps.
-- Separate from last_plan_serial (V86), which is what the edge reports back as
-- applied.
ALTER TABLE edge_hubs ADD COLUMN plan_serial INTEGER NOT NULL DEFAULT 0;
ALTER TABLE edge_hubs ADD COLUMN plan_hash VARCHAR(64);
