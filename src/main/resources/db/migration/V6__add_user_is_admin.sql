-- V6: org-user admin flag
-- The ENV-bootstrap admin is always admin and has no row in `users` (see V5).
-- Org users (OIDC-provisioned or local org accounts) default to non-admin;
-- existing admins can promote them via the Users view. Later, Entra-ID role
-- claims will be mapped onto this column at login time (see roadmap v2).
--
-- Portable SQL: SQLite and Postgres both accept BOOLEAN with DEFAULT FALSE.

ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;
