-- V89: edge-endpoint-fields (ADR-I-0003) — what clients dial at an edge, and
-- whose key they trust. The same two things settings.wg_server_endpoint and
-- settings.wg_server_public_key say about the central hub, which is the point:
-- an edge is described by exactly what a hub is described by.
--
-- Nullable, because an edge is normally created (name, coordinates) before the
-- machine behind it exists. They are required at the moment they are first
-- needed instead: PeerService refuses to assign a peer to an edge that has
-- neither, since that peer's configuration could not be rendered.
ALTER TABLE edge_hubs ADD COLUMN endpoint VARCHAR(255);
ALTER TABLE edge_hubs ADD COLUMN server_public_key VARCHAR(44);

-- peer_id answered "which peer is the edge", a question ADR-I-0003 retires:
-- the edge authenticates to the hub as an API client (api_key_id, bound to
-- this edge via api_key_scopes.edge_hub_id), not as a WireGuard peer of it.
-- It was never written by anything, so there is no data to carry over.
--
-- Dropped rather than left for later on purpose: an unused column that looks
-- like an answer invites rebuilding the confusion between access reach and
-- physical attachment — the very mix-up this ADR corrects.
ALTER TABLE edge_hubs DROP COLUMN peer_id;
