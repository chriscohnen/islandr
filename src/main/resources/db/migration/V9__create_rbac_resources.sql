-- V9: RBAC0 (NIST) + per-resource ACL
-- All tables introduced together: they form one atomic data model and any
-- partial subset would be useless. See docs/adr/0006-resource-level-acl.md
-- for the rationale; docs/prd.md §7 has the entity diagram.
--
-- Portable SQL only (SQLite + Postgres). FKs use ON DELETE CASCADE where the
-- child is meaningless without the parent (a port without its resource, a
-- grant without its role), and ON DELETE RESTRICT where the parent ought to
-- be cleaned up explicitly (a site with resources should not silently take
-- them down — the admin should remove resources first).

-- ---------------------------------------------------------------------------
-- Site — organisational grouping for resources. CIDR is informational (used
-- by the UI for grouping + as a hint for the Hub-side AllowedIPs config);
-- it does NOT participate in nftables enforcement (ADR-0006 §Decision 1).
-- ---------------------------------------------------------------------------
CREATE TABLE sites (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    cidr        VARCHAR(50)  NOT NULL,
    description TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX ix_sites_name ON sites (name);

-- ---------------------------------------------------------------------------
-- Resource — a named host inside a site. (site_id, ip) uniqueness keeps the
-- admin from accidentally registering Terminal-01 under two names; the same
-- IP can legitimately appear under two different sites if an org reuses
-- private ranges across remote networks.
-- ---------------------------------------------------------------------------
CREATE TABLE resources (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    site_id     VARCHAR(36)  NOT NULL REFERENCES sites(id),
    name        VARCHAR(255) NOT NULL,
    ip          VARCHAR(45)  NOT NULL,
    description TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_resources_site ON resources (site_id);
CREATE UNIQUE INDEX ix_resources_site_ip ON resources (site_id, ip);

-- ---------------------------------------------------------------------------
-- ResourcePort — one row per reachable (transport, port) on a resource.
-- protocol is a UI label ('RDP', 'SSH', 'SFTP', 'HTTP', 'CUSTOM'). NOT enforced
-- (see ADR-0006 §"What this does not protect against"). label is optional
-- free text the admin can use to disambiguate ("RDP for IT" vs "RDP for VPN").
-- ---------------------------------------------------------------------------
CREATE TABLE resource_ports (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    resource_id VARCHAR(36)  NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    port        INTEGER      NOT NULL CHECK (port BETWEEN 1 AND 65535),
    transport   VARCHAR(8)   NOT NULL CHECK (transport IN ('tcp', 'udp')),
    protocol    VARCHAR(32)  NOT NULL,
    label       VARCHAR(255) NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_resource_ports_resource ON resource_ports (resource_id);
CREATE UNIQUE INDEX ix_resource_ports_tuple ON resource_ports (resource_id, port, transport);

-- ---------------------------------------------------------------------------
-- Role — RBAC0 core entity. Name is unique; description is freeform.
-- 'ADMIN' / 'END_USER' system-role notion from PRD F-19 stays orthogonal:
-- it lives on users.is_admin (V6). These RBAC roles are about resource
-- access via the tunnel, not about app permissions.
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX ix_roles_name ON roles (name);

-- ---------------------------------------------------------------------------
-- User × Role membership. Many-to-many: a user can be in many roles, a role
-- can hold many users. Composite primary key serves as the dedup constraint
-- (no duplicate row for the same user-role pair).
-- ---------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id VARCHAR(36) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id);

-- ---------------------------------------------------------------------------
-- RoleResourceGrant — a role's permission to reach a resource.
-- all_ports=true means "every current and future port of this resource"
-- (ADR-0006 R-054: widens silently when new ports get added, audited).
-- all_ports=false plus an entry in role_resource_grant_ports for each
-- specific port means "only these ports".
-- ---------------------------------------------------------------------------
CREATE TABLE role_resource_grants (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    role_id     VARCHAR(36)  NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    resource_id VARCHAR(36)  NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    all_ports   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_grants_role ON role_resource_grants (role_id);
CREATE INDEX ix_grants_resource ON role_resource_grants (resource_id);
CREATE UNIQUE INDEX ix_grants_role_resource ON role_resource_grants (role_id, resource_id);

-- ---------------------------------------------------------------------------
-- Grant × Port. Only present when the grant is port-limited (all_ports=false).
-- When all_ports=true this table is empty for that grant — the join at
-- rule-generation time treats all_ports as a wildcard against the
-- resource's current resource_ports rows.
-- ---------------------------------------------------------------------------
CREATE TABLE role_resource_grant_ports (
    grant_id VARCHAR(36) NOT NULL REFERENCES role_resource_grants(id) ON DELETE CASCADE,
    port_id  VARCHAR(36) NOT NULL REFERENCES resource_ports(id)       ON DELETE CASCADE,
    PRIMARY KEY (grant_id, port_id)
);
CREATE INDEX ix_grant_ports_port ON role_resource_grant_ports (port_id);
