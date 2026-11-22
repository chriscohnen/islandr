-- edge-status-plan-fields (ADR-I-0005 §5): what the edge agent reports about the plan it
-- applied, next to last_plan_serial / last_applied_at / last_sync_at (V86).
ALTER TABLE edge_hubs ADD COLUMN plan_rejected VARCHAR(500);
ALTER TABLE edge_hubs ADD COLUMN skipped_peers INTEGER;
ALTER TABLE edge_hubs ADD COLUMN agent_version VARCHAR(64);
