-- V7: avatar provenance
-- Tracks which source the cached avatar_bytes came from. Lets the login flow
-- decide whether the cached image is still authoritative or needs replacing
-- (e.g. when the Gravatar toggle flips, or when a user removes their Gravatar
-- and we should fall back to the OIDC picture).
--
-- Values:
--   'gravatar' — bytes came from gravatar.com via the AvatarFetcher
--   'oidc'     — bytes came from the MS Graph / Google picture pipeline
--   NULL       — no cached avatar yet
--
-- Portable SQL: TEXT for the enum-ish column (SQLite doesn't enforce CHECK
-- well across versions, and the value set is small enough not to need one).

ALTER TABLE users ADD COLUMN avatar_source VARCHAR(16) NULL;
