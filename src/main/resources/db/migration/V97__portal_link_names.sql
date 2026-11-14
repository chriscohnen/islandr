-- resource-links-by-name (1.4.0): the portal may build its links from a
-- resource's DNS name instead of its IP. Off by default; plain ADD COLUMN
-- (settings and resource_ports are foreign-key parents, no table rebuild).
ALTER TABLE settings ADD COLUMN portal_links_use_dns_name INTEGER NOT NULL DEFAULT 0;

-- Per-port override of that switch. Null = follow the switch, 'ip' = always
-- the IP, otherwise one of the resource's own DNS labels (main name or alias).
ALTER TABLE resource_ports ADD COLUMN link_host VARCHAR(63);
