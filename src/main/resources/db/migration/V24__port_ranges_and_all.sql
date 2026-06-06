-- v0.7.0: port ranges and all-ports sentinel.
--
-- port = 0  → "all ports" (no dport filter in nftables).
-- port_end  → non-null means range [port, port_end], enforced in Java.
-- transport → 'both' generates two rules (tcp + udp).
--
-- SQLite cannot ALTER CHECK constraints. We recreate both tables so that the
-- widened CHECK values take effect. SQLite does not enforce foreign keys by
-- default (requires PRAGMA foreign_keys = ON per connection), so the DROP of
-- the old table is safe even with the role_resource_grant_ports reference.

-- ── resource_ports ────────────────────────────────────────────────────────
CREATE TABLE resource_ports_v24 (
    id          VARCHAR(36)  PRIMARY KEY,
    resource_id VARCHAR(36)  NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    port        INTEGER      NOT NULL CHECK (port BETWEEN 0 AND 65535),
    port_end    INTEGER,
    transport   VARCHAR(8)   NOT NULL CHECK (transport IN ('tcp', 'udp', 'both')),
    protocol    VARCHAR(32)  NOT NULL,
    label       VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL
);

INSERT INTO resource_ports_v24 (id, resource_id, port, port_end, transport, protocol, label, created_at)
    SELECT id, resource_id, port, NULL, transport, protocol, label, created_at
    FROM resource_ports;

DROP TABLE resource_ports;

ALTER TABLE resource_ports_v24 RENAME TO resource_ports;

CREATE INDEX ix_resource_ports_resource ON resource_ports (resource_id);
CREATE UNIQUE INDEX ix_resource_ports_tuple
    ON resource_ports (resource_id, port, transport, COALESCE(port_end, 0));

-- ── port_group_members ────────────────────────────────────────────────────
CREATE TABLE port_group_members_v24 (
    id            VARCHAR(36)  PRIMARY KEY,
    port_group_id VARCHAR(36)  NOT NULL REFERENCES port_groups(id) ON DELETE CASCADE,
    port          INTEGER      NOT NULL CHECK (port BETWEEN 0 AND 65535),
    port_end      INTEGER,
    transport     VARCHAR(8)   NOT NULL CHECK (transport IN ('tcp', 'udp', 'both')),
    protocol      VARCHAR(32)  NOT NULL,
    label         VARCHAR(255)
);

INSERT INTO port_group_members_v24 (id, port_group_id, port, port_end, transport, protocol, label)
    SELECT id, port_group_id, port, NULL, transport, protocol, label
    FROM port_group_members;

DROP TABLE port_group_members;

ALTER TABLE port_group_members_v24 RENAME TO port_group_members;

CREATE INDEX ix_pgm_group ON port_group_members (port_group_id);
CREATE UNIQUE INDEX ix_pgm_tuple
    ON port_group_members (port_group_id, port, transport, COALESCE(port_end, 0));
