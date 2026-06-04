-- V10: port groups (templates for typical resource port sets)
-- A port group is just a reusable list of (port, transport, protocol, label)
-- tuples. Applying a group to a resource COPIES the tuples into
-- resource_ports — there is NO live link. That keeps the model predictable:
-- changing the group later does not mutate already-configured resources,
-- and resources can mix grouped + ad-hoc ports without special cases.
--
-- Seeded with five common templates so a fresh deployment starts with the
-- usual building blocks instead of an empty list.

CREATE TABLE port_groups (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX ix_port_groups_name ON port_groups (name);

CREATE TABLE port_group_members (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    port_group_id VARCHAR(36)  NOT NULL REFERENCES port_groups(id) ON DELETE CASCADE,
    port          INTEGER      NOT NULL CHECK (port BETWEEN 1 AND 65535),
    transport     VARCHAR(8)   NOT NULL CHECK (transport IN ('tcp', 'udp')),
    protocol      VARCHAR(32)  NOT NULL,
    label         VARCHAR(255) NULL
);
CREATE INDEX ix_pgm_group ON port_group_members (port_group_id);
CREATE UNIQUE INDEX ix_pgm_tuple ON port_group_members (port_group_id, port, transport);

-- Seed: five everyday templates. The operator can rename/edit/delete them.
-- UUIDs are stable so the seed is idempotent under SQLite + Postgres alike.
INSERT INTO port_groups (id, name, description, created_at) VALUES
  ('00000000-0000-0000-0000-port-group-prn', 'Drucker_Standard_Ports', 'Netzwerkdrucker: RAW (JetDirect) + IPP', CURRENT_TIMESTAMP),
  ('00000000-0000-0000-0000-port-group-web', 'Web_Standard',           'HTTP + HTTPS',                          CURRENT_TIMESTAMP),
  ('00000000-0000-0000-0000-port-group-rdp', 'RDP',                    'Windows-Remote-Desktop',                CURRENT_TIMESTAMP),
  ('00000000-0000-0000-0000-port-group-ssh', 'SSH',                    'Secure Shell',                          CURRENT_TIMESTAMP),
  ('00000000-0000-0000-0000-port-group-smb', 'SMB',                    'Windows-Dateifreigabe',                 CURRENT_TIMESTAMP);

INSERT INTO port_group_members (id, port_group_id, port, transport, protocol, label) VALUES
  ('00000000-0000-0000-0000-pgm-prn-9100', '00000000-0000-0000-0000-port-group-prn', 9100, 'tcp', 'RAW', 'JetDirect / RAW-Druck'),
  ('00000000-0000-0000-0000-pgm-prn-0631', '00000000-0000-0000-0000-port-group-prn', 631,  'tcp', 'IPP', 'Internet Printing Protocol'),
  ('00000000-0000-0000-0000-pgm-web-0080', '00000000-0000-0000-0000-port-group-web', 80,   'tcp', 'HTTP', NULL),
  ('00000000-0000-0000-0000-pgm-web-0443', '00000000-0000-0000-0000-port-group-web', 443,  'tcp', 'HTTPS', NULL),
  ('00000000-0000-0000-0000-pgm-rdp-3389', '00000000-0000-0000-0000-port-group-rdp', 3389, 'tcp', 'RDP', NULL),
  ('00000000-0000-0000-0000-pgm-ssh-0022', '00000000-0000-0000-0000-port-group-ssh', 22,   'tcp', 'SSH', NULL),
  ('00000000-0000-0000-0000-pgm-smb-0445', '00000000-0000-0000-0000-port-group-smb', 445,  'tcp', 'SMB', NULL);
