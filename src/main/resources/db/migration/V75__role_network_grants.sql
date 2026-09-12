-- V75: role grants scoped to an entire site network (its full CIDR), not a
-- concrete resource or a resource type — "this role's peers can reach every
-- host in this site's subnet, on every port/protocol, present or future."
-- Always full-reach: no all_ports column (nothing to toggle), no port join
-- table (a CIDR-wide rule cannot sensibly carry a fixed port list — same
-- reasoning role_resource_type_grants already applied, taken one level
-- further). See ADR-0029.
--
-- Unique (role_id, site_id): re-granting the same network is a no-op.
CREATE TABLE role_network_grants (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    role_id    VARCHAR(36) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    site_id    VARCHAR(36) NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    created_at TIMESTAMP   NOT NULL,
    UNIQUE (role_id, site_id)
);

CREATE INDEX ix_role_network_grants_role ON role_network_grants (role_id);
CREATE INDEX ix_role_network_grants_site ON role_network_grants (site_id);
