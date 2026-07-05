-- Local user passwords (F-01a): PBKDF2 PHC string, nullable.
-- NULL = user has no local password (authenticates via OIDC or is ENV-admin only).
ALTER TABLE users ADD COLUMN password_hash VARCHAR;
