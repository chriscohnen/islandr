-- Extend private_key_pem from VARCHAR(44) to VARCHAR(128) to accommodate the
-- encrypted format: "enc$" prefix (4 chars) + base64(12-byte IV || ciphertext || 16-byte GCM tag).
-- A 44-char plaintext WireGuard key encrypts to ~100 chars; 128 gives headroom.
--
-- SQLite does not support ALTER COLUMN TYPE, so we use rename/add/copy/drop.
-- All four statements are valid standard SQL and run unchanged on PostgreSQL.

ALTER TABLE peers RENAME COLUMN private_key_pem TO private_key_pem_old;
ALTER TABLE peers ADD COLUMN private_key_pem VARCHAR(128);
UPDATE peers SET private_key_pem = private_key_pem_old;
ALTER TABLE peers DROP COLUMN private_key_pem_old;
