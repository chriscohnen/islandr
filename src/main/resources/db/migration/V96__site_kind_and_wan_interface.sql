-- egress-via-hub (1.4.0): a site can be "external" — a partner's public network
-- reached through the hub's own WAN address, with no gateway peer. Existing
-- sites are all "internal". Plain ADD COLUMN, no table rebuild: sites is a
-- foreign-key parent (resources, grants), and a rebuild with foreign keys on
-- would cascade (see the ipv6-only-86 note).
ALTER TABLE sites ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'internal';

-- Override for the hub's WAN interface. Null = detected from the kernel's
-- routing table (the default route's interface) at ruleset build time.
ALTER TABLE settings ADD COLUMN wan_interface VARCHAR(15);
