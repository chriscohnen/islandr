CREATE TABLE site_resource_grants (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    site_id     VARCHAR(36)  NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    resource_id VARCHAR(36)  NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    all_ports   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_site_grants_site ON site_resource_grants (site_id);
CREATE INDEX ix_site_grants_resource ON site_resource_grants (resource_id);
CREATE UNIQUE INDEX ix_site_grants_site_resource ON site_resource_grants (site_id, resource_id);

CREATE TABLE site_resource_grant_ports (
    grant_id VARCHAR(36) NOT NULL REFERENCES site_resource_grants(id) ON DELETE CASCADE,
    port_id  VARCHAR(36) NOT NULL REFERENCES resource_ports(id)       ON DELETE CASCADE,
    PRIMARY KEY (grant_id, port_id)
);
CREATE INDEX ix_site_grant_ports_port ON site_resource_grant_ports (port_id);
