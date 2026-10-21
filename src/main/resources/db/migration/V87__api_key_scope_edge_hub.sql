-- V87: edge-psk-exposure (loop/TASK.md) — the first API-key scope bound to
-- a single object rather than the whole install. The "Offene Entscheidungen"
-- apikey-scopes catalog note (2026-10-02) anticipated this and reserved
-- api_key_scopes.site_id for it, but that was written before edge_hubs
-- existed as its own table — a scope naming "which edge may this key speak
-- for" belongs on edge_hub_id, one level up from site_id (an edge serves
-- several sites, so a site-level binding would be too narrow).
--
-- ON DELETE CASCADE, not SET NULL (unlike sites.edge_hub_id in V86): a
-- scope bound to an edge hub that no longer exists is not "now applies
-- everywhere" (what NULL would mean for this column) — it is meaningless
-- and must go with the edge hub, the same reasoning api_key_scopes already
-- applies to api_key_id itself.
ALTER TABLE api_key_scopes ADD COLUMN edge_hub_id VARCHAR(36) REFERENCES edge_hubs(id) ON DELETE CASCADE;

CREATE INDEX ix_api_key_scopes_edge_hub ON api_key_scopes (edge_hub_id);
