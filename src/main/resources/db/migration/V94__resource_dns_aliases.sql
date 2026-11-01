-- V94: additional DNS names per resource. A reverse proxy on one host decides
-- by FQDN which app is meant, so a resource needs more than one name. The
-- aliases share the main name's scope (dns_flat) and the resource's IP.
CREATE TABLE resource_dns_aliases (
    resource_id VARCHAR(36) NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    name        VARCHAR(63) NOT NULL,
    PRIMARY KEY (resource_id, name)
);
CREATE INDEX ix_resource_dns_aliases_name ON resource_dns_aliases (name);
