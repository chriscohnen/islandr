-- V51: role grants scoped by resource type within a site, e.g. "all printers
-- in Homeoffice", instead of one row per concrete resource. Additive only —
-- effective access is the union of role_resource_grants (per-resource) and
-- role_resource_type_grants (per-type); a type-grant is always all-ports
-- (see RuleBuilder/MyAccessResource/RdpGrantService, which expand a matching
-- type-grant into the same all-ports shape a concrete grant already has).
--
-- Unique (role_id, site_id, resource_type): re-granting the same rule is a
-- no-op, not a duplicate row.
CREATE TABLE role_resource_type_grants (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    role_id       VARCHAR(36)  NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    site_id       VARCHAR(36)  NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    resource_type VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    UNIQUE (role_id, site_id, resource_type)
);

CREATE INDEX ix_role_resource_type_grants_role ON role_resource_type_grants (role_id);
CREATE INDEX ix_role_resource_type_grants_site_type ON role_resource_type_grants (site_id, resource_type);
