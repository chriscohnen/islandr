CREATE TABLE user_resource_grants (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_id VARCHAR(36)  NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    all_ports   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_user_grants_user ON user_resource_grants (user_id);
CREATE INDEX ix_user_grants_resource ON user_resource_grants (resource_id);
CREATE UNIQUE INDEX ix_user_grants_user_resource ON user_resource_grants (user_id, resource_id);

CREATE TABLE user_resource_grant_ports (
    grant_id VARCHAR(36) NOT NULL REFERENCES user_resource_grants(id) ON DELETE CASCADE,
    port_id  VARCHAR(36) NOT NULL REFERENCES resource_ports(id)       ON DELETE CASCADE,
    PRIMARY KEY (grant_id, port_id)
);
CREATE INDEX ix_user_grant_ports_port ON user_resource_grant_ports (port_id);
