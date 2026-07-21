-- V47: move geocoding from sites (logical network) to peers (the physical
-- gateway device that hosts it).
--
-- A site is a logical CIDR grouping — it has no physical location of its own.
-- The site-type peer routing it is the actual box sitting in a rack or a
-- home office, so that's what a coordinate describes. One site peer can also
-- be the gateway for more than one site (several CIDRs behind the same
-- physical device), which made per-site coordinates redundant at best and
-- inconsistent at worst.

ALTER TABLE peers ADD COLUMN lat DOUBLE PRECISION;
ALTER TABLE peers ADD COLUMN lng DOUBLE PRECISION;
ALTER TABLE peers ADD COLUMN location_label VARCHAR(255);

-- Backfill: for each site peer that is some site's gateway, adopt that
-- site's coordinates. If it gateways more than one geocoded site, whichever
-- one the subquery happens to pick wins — there was never a single correct
-- answer once one peer served several sites, which is exactly the bug this
-- migration fixes going forward.
UPDATE peers SET
    lat = (SELECT s.lat FROM sites s WHERE s.gateway_peer_id = peers.id AND s.lat IS NOT NULL LIMIT 1),
    lng = (SELECT s.lng FROM sites s WHERE s.gateway_peer_id = peers.id AND s.lng IS NOT NULL LIMIT 1),
    location_label = (SELECT s.location_label FROM sites s WHERE s.gateway_peer_id = peers.id AND s.location_label IS NOT NULL LIMIT 1)
WHERE EXISTS (SELECT 1 FROM sites s WHERE s.gateway_peer_id = peers.id);

ALTER TABLE sites DROP COLUMN lat;
ALTER TABLE sites DROP COLUMN lng;
ALTER TABLE sites DROP COLUMN location_label;
