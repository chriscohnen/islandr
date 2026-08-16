-- V62: Peer-Scheduler (#47) — time-windowed peer enable/disable.
--
-- peer_schedules: one optional row per peer describing a recurring weekly
-- window during which the peer should be enabled. weekday_mask is a bitmask
-- (bit0=Monday ... bit6=Sunday). active_from/active_to are stored as plain
-- "HH:mm" strings (VARCHAR(5)), not a native TIME column — same reasoning as
-- V44's `day` column: SQLite's JDBC driver doesn't reliably round-trip its
-- native TIME/DATE types, but a fixed-width zero-padded string sorts/compares
-- correctly as a string on both SQLite (dev) and Postgres (prod), per ADR-0004.
-- active_from > active_to means an overnight-spanning window (e.g. 22:00-06:00).
--
-- Absence of a peer_schedules row means "no recurring schedule" — the peer's
-- enabled state is then governed only by valid_until (below) and manual
-- toggles. One row per peer (UNIQUE peer_id) keeps this v1 to a single daily
-- window; a future multi-window or self-service-request feature can add its
-- own table referencing peer_id without touching this one.
CREATE TABLE peer_schedules (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    peer_id      VARCHAR(36) NOT NULL REFERENCES peers(id) ON DELETE CASCADE,
    weekday_mask INTEGER     NOT NULL,
    active_from  VARCHAR(5)  NOT NULL,
    active_to    VARCHAR(5)  NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP   NOT NULL
);
CREATE UNIQUE INDEX ix_peer_schedules_peer ON peer_schedules (peer_id);

-- valid_until: a one-time, terminal expiry independent of any recurring
-- schedule — the special case that closes #10 (contractor/trial peers should
-- really be gone, not resurrectable by a schedule window reopening).
ALTER TABLE peers ADD COLUMN valid_until TIMESTAMP NULL;

-- enabled_source tracks who last changed `enabled` ("manual" | "schedule" |
-- NULL = never toggled by either path since creation) so PeerScheduleJob can
-- tell a manual disable apart from one it caused itself: a manual toggle
-- holds until the schedule's next open<->close transition, rather than being
-- silently overridden by the very next minute-tick.
ALTER TABLE peers ADD COLUMN enabled_source VARCHAR(16) NULL;
