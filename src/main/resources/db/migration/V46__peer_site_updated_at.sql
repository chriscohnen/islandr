-- V46: last-modified tracking for peers and sites.
--
-- Both tables only had created_at, which stays constant for the lifetime
-- of the row and says nothing about when the peer/site config last
-- actually changed. Backfill existing rows with created_at so nothing
-- reads as "never modified" right after this migration runs.
--
-- SQLite's ALTER TABLE ADD COLUMN rejects CURRENT_TIMESTAMP as a default —
-- "Cannot add a column with non-constant default" — it only accepts a
-- literal constant there. Use a placeholder literal, then immediately
-- overwrite every row via UPDATE; the placeholder never persists.

ALTER TABLE peers ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT '1970-01-01 00:00:00';
UPDATE peers SET updated_at = created_at;

ALTER TABLE sites ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT '1970-01-01 00:00:00';
UPDATE sites SET updated_at = created_at;
