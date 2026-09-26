-- V81: self-service "soft delete" for a peer (loop/TASK.md task
-- users-self-delete-peer). A user can ask to remove their own device from
-- "My access"; the row stays until an admin performs the real DELETE.
--
-- NULL (the default) = no request. Non-null = when the user asked. Setting
-- it also disables the peer (PeerService.requestDeletion), so it stops
-- working immediately rather than merely disappearing from the user's own
-- list while quietly staying reachable.
ALTER TABLE peers ADD COLUMN deletion_requested_at TIMESTAMP;
