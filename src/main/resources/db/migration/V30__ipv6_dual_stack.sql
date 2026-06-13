-- IPv6 dual-stack support: add optional IPv6 subnet to settings,
-- and optional IPv6 address per peer.
-- NULL = IPv4-only (backward compatible with all existing rows).

ALTER TABLE settings ADD COLUMN wg_subnet6 VARCHAR(50);
ALTER TABLE peers    ADD COLUMN assigned_ip6 VARCHAR(45);
