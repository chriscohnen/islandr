-- V88: peer-hub-assignment (ADR-I-0003) — a peer belongs to exactly one hub.
-- NULL = the central hub, which stays implicit here exactly as it does in
-- sites.edge_hub_id (V86) and has no row in edge_hubs.
--
-- Deliberately NO "ON DELETE SET NULL", unlike sites.edge_hub_id. For a site
-- that clause is right: an edge going away must fall the site back to the
-- central hub rather than take its resources down. For a peer it would be
-- wrong — the assignment is immutable by decision, and re-homing a peer means
-- moving its address into another routing domain while both sides still hold
-- state about it. SET NULL would perform exactly that move as a side effect of
-- deleting an edge, i.e. a back door around the rule. The default (NO ACTION)
-- refuses instead; EdgeHubService.delete() checks first and answers 409 with a
-- reason, so the FK is the backstop, not the error message.
ALTER TABLE peers ADD COLUMN edge_hub_id VARCHAR(36) REFERENCES edge_hubs(id);

CREATE INDEX ix_peers_edge_hub ON peers (edge_hub_id);
