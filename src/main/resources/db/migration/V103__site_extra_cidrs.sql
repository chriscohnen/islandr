-- site-multi-cidr (1.5.0): an external site (a partner) carries more than one address
-- range. The primary stays in sites.cidr; further ones live here, in order. Only
-- external sites may have them (enforced in SiteService). Additive on purpose: replacing
-- sites.cidr would mean rebuilding the table, and with foreign keys on that cascades into
-- resources and grants.
CREATE TABLE site_extra_cidrs (
    id       VARCHAR(36) NOT NULL PRIMARY KEY,
    site_id  VARCHAR(36) NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    cidr     VARCHAR(50) NOT NULL,
    position INTEGER NOT NULL,
    UNIQUE (site_id, cidr)
);
CREATE INDEX ix_site_extra_cidrs_site ON site_extra_cidrs (site_id);
