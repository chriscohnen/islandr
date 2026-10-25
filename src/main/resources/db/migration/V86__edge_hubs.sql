-- V86: edge-hub-entity (loop/TASK.md, 1.2.0) — a second WireGuard hub at a
-- remote site, modelled as its own entity rather than a flag on `sites`
-- (a site has no physical location of its own and one gateway peer can
-- serve more than one site — the same reasoning V47 already applied to
-- geocoding). The central hub stays implicit: it has no row here, its
-- coordinates remain on settings.hub_lat/hub_lon.
--
-- peer_id, api_key_id, last_sync_at, last_plan_serial and last_applied_at
-- are unused this slice — added now on purpose so the edge-plan-endpoint/
-- edge-status-report tasks (deferred to a later cycle, see loop/TASK.md)
-- don't need a second migration. Same precedent as api_key_scopes.site_id
-- (V85).
CREATE TABLE edge_hubs (
    id                 VARCHAR(36) NOT NULL PRIMARY KEY,
    name               VARCHAR(255) NOT NULL UNIQUE,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    lat                REAL        NOT NULL,
    lng                REAL        NOT NULL,
    location_label     VARCHAR(255),
    peer_id            VARCHAR(36) REFERENCES peers(id) ON DELETE SET NULL,
    api_key_id         VARCHAR(36) REFERENCES api_keys(id) ON DELETE SET NULL,
    last_sync_at       TIMESTAMP,
    last_plan_serial   INTEGER,
    last_applied_at    TIMESTAMP,
    created_at         TIMESTAMP   NOT NULL,
    updated_at         TIMESTAMP   NOT NULL
);

-- NULL = served by the central hub. ON DELETE SET NULL, not CASCADE: an
-- edge hub going away must fall a site back to the central hub, never take
-- the site (and its resources/grants) down with it.
ALTER TABLE sites ADD COLUMN edge_hub_id VARCHAR(36) REFERENCES edge_hubs(id) ON DELETE SET NULL;

CREATE INDEX ix_sites_edge_hub ON sites (edge_hub_id);
