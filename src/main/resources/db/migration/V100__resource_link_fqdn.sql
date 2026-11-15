-- resource-link-fqdn (1.4.0): a partner machine in an external network carries
-- the public name it is known by (e.g. icanhazip.com), used for portal links.
-- Not unique on purpose: two addresses of one service share the name. The hub's
-- resolver never answers it; the upstream resolver does.
ALTER TABLE resources ADD COLUMN link_fqdn VARCHAR(253);
