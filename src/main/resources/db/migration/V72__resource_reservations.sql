-- Self-service JIT access + exclusive-capacity resources (issue #72).
--
-- Two independent layers, deliberately: the existing role/user grants keep
-- deciding *who may see and request a resource at all* (RBAC is unchanged),
-- while a reservation decides *who holds the exclusive slot right now*. A
-- resource only takes part in the second layer once max_concurrent_users is
-- set — NULL means unlimited, which is every resource that exists today, so
-- this migration is behaviour-neutral until an admin opts a resource in.

-- NULL = unlimited concurrent users, i.e. today's behaviour. A non-null value
-- makes the resource reservation-gated: a standing grant alone stops being
-- enough to reach it, an active reservation is additionally required.
ALTER TABLE resources ADD COLUMN max_concurrent_users INTEGER;

-- Ceiling on how long any single self-service reservation may run, in
-- minutes. NULL = no extra ceiling beyond what the duration picker offers.
ALTER TABLE resources ADD COLUMN max_reservation_minutes INTEGER;

-- When true (the default), a request that fits inside the remaining capacity
-- is granted immediately; when false every request waits for an admin
-- decision even if the resource is idle.
-- INTEGER, not BOOLEAN, to match the entity's columnDefinition — same
-- convention as resources.dns_flat (V57) and roles.auto_all (V38). Hibernate's
-- schema validation rejects the mismatch outright at boot.
ALTER TABLE resources ADD COLUMN auto_approve_reservations INTEGER NOT NULL DEFAULT 1;

CREATE TABLE resource_reservations (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    resource_id       VARCHAR(36)  NOT NULL,
    user_id           VARCHAR(36)  NOT NULL,
    -- pending | active | rejected | cancelled | expired. Only 'active' rows
    -- with ends_at in the future actually confer access.
    status            VARCHAR(16)  NOT NULL,
    requested_minutes INTEGER      NOT NULL,
    requested_at      TIMESTAMP    NOT NULL,
    -- Both set when a reservation becomes active (immediately on
    -- auto-approve, at decision time otherwise), NULL while pending.
    starts_at         TIMESTAMP,
    ends_at           TIMESTAMP,
    -- Who approved/rejected, and when. NULL for auto-approved rows: nobody
    -- decided those, and the audit trail should not imply an admin did.
    decided_by        VARCHAR(255),
    decided_at        TIMESTAMP,
    CONSTRAINT fk_reservations_resource FOREIGN KEY (resource_id)
        REFERENCES resources (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservations_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

-- The capacity check ("how many active reservations does this resource have
-- right now") and the enforcement lookup in RuleBuilder both hit
-- (resource_id, status) on every ruleset recompute.
CREATE INDEX ix_reservations_resource_status ON resource_reservations (resource_id, status);
CREATE INDEX ix_reservations_user_status ON resource_reservations (user_id, status);
-- ReservationExpiryJob scans for due rows once a minute.
CREATE INDEX ix_reservations_status_ends ON resource_reservations (status, ends_at);
