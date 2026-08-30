-- User-level access expiry (issue #53).
--
-- The Peer-Scheduler (#47) time-boxes a *device*. That leaves a hole when
-- self-service peer creation is on: a contractor whose peer just expired logs
-- into the portal and creates a fresh one with no expiry, fully working. The
-- time-boxed grant is defeated by one click.
--
-- Expiry therefore also belongs on the person. NULL — every user that exists
-- today — means no expiry, unchanged behaviour.
--
-- Deliberately separate from users.enabled rather than folded into it: enabled
-- records an admin's standing decision, valid_until records a deadline. Access
-- requires both, and keeping them apart means an admin extending the deadline
-- does not silently undo a deliberate disable (or vice versa).
ALTER TABLE users ADD COLUMN valid_until TIMESTAMP;

-- UserAccessExpiryJob scans for users whose deadline has passed and that are
-- still marked enabled, once a minute.
CREATE INDEX ix_users_valid_until ON users (valid_until);
