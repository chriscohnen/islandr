-- V46: last-modified tracking for peers and sites.
--
-- Both tables only had created_at, which stays constant for the lifetime
-- of the row and says nothing about when the peer/site config last
-- actually changed. Backfill existing rows with created_at so nothing
-- reads as "never modified" right after this migration runs.

ALTER TABLE peers ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE peers SET updated_at = created_at;

ALTER TABLE sites ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE sites SET updated_at = created_at;
