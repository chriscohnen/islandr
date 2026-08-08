-- V59: rotation-event timestamps for a peer's WireGuard keypair and PSK
-- (issue #46) — lets an admin see whether/when a peer's key material was
-- last rotated, the missing piece for using rotation as a compromised-
-- device response instead of delete-and-recreate. Null = never rotated
-- since creation (every existing peer, and any peer whose key/PSK has
-- never been touched by an explicit rotate action).
ALTER TABLE peers ADD COLUMN key_rotated_at TIMESTAMP;
ALTER TABLE peers ADD COLUMN psk_rotated_at TIMESTAMP;
