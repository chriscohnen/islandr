-- Moves the exclusive-capacity layer (issue #72) from the resource down to the
-- individual port.
--
-- V72 put the limit on the resource, which makes a whole host exclusive at
-- once. That is wrong for the case this feature exists for: a box can very
-- well have one seat on RDP while its SSH port stays freely usable, and two
-- people should be able to work on it that way. The reservation therefore
-- binds a *port*, not a machine, and each port carries its own capacity.
--
-- resource_reservations is recreated rather than altered: it was introduced in
-- V72, which has never appeared in a released version, so no installation can
-- hold rows worth migrating. Recreating buys a real NOT NULL foreign key on
-- port_id instead of a nullable column that code would have to keep
-- second-guessing.

ALTER TABLE resource_ports ADD COLUMN max_concurrent_users INTEGER;
ALTER TABLE resource_ports ADD COLUMN max_reservation_minutes INTEGER;
ALTER TABLE resource_ports ADD COLUMN auto_approve_reservations INTEGER NOT NULL DEFAULT 1;

-- Carry over anything V72 already set at resource level, so a branch install
-- configured in the meantime keeps its intent: the limit applied to the whole
-- resource, which is every one of its ports.
UPDATE resource_ports
   SET max_concurrent_users = (SELECT r.max_concurrent_users FROM resources r
                                WHERE r.id = resource_ports.resource_id),
       max_reservation_minutes = (SELECT r.max_reservation_minutes FROM resources r
                                   WHERE r.id = resource_ports.resource_id),
       auto_approve_reservations = (SELECT r.auto_approve_reservations FROM resources r
                                     WHERE r.id = resource_ports.resource_id)
 WHERE EXISTS (SELECT 1 FROM resources r
                WHERE r.id = resource_ports.resource_id
                  AND r.max_concurrent_users IS NOT NULL);

DROP TABLE resource_reservations;

CREATE TABLE resource_reservations (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    port_id           VARCHAR(36)  NOT NULL,
    -- Denormalised alongside port_id: every enforcement and display path needs
    -- the resource too (its IP, its name, its grants), and carrying it here
    -- keeps RuleBuilder from joining resource_ports on every ruleset rebuild.
    resource_id       VARCHAR(36)  NOT NULL,
    user_id           VARCHAR(36)  NOT NULL,
    -- pending | active | rejected | cancelled | expired. Only 'active' rows
    -- with ends_at in the future actually confer access.
    status            VARCHAR(16)  NOT NULL,
    requested_minutes INTEGER      NOT NULL,
    requested_at      TIMESTAMP    NOT NULL,
    starts_at         TIMESTAMP,
    ends_at           TIMESTAMP,
    decided_by        VARCHAR(255),
    decided_at        TIMESTAMP,
    CONSTRAINT fk_reservations_port FOREIGN KEY (port_id)
        REFERENCES resource_ports (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservations_resource FOREIGN KEY (resource_id)
        REFERENCES resources (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservations_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX ix_reservations_port_status ON resource_reservations (port_id, status);
CREATE INDEX ix_reservations_user_status ON resource_reservations (user_id, status);
CREATE INDEX ix_reservations_status_ends ON resource_reservations (status, ends_at);

-- The resource-level columns V72 added are now dead: capacity lives on the
-- port. Dropped rather than left in place so there is one answer to "is this
-- reservable", not two that can disagree.
ALTER TABLE resources DROP COLUMN max_concurrent_users;
ALTER TABLE resources DROP COLUMN max_reservation_minutes;
ALTER TABLE resources DROP COLUMN auto_approve_reservations;
