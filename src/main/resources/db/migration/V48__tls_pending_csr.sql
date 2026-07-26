-- V48: Origin Server Certificate CSR generation (#42).
--
-- An admin can have islandr generate a private key + PKCS#10 CSR instead of
-- bringing their own, then paste back only the CA-signed certificate once it
-- arrives -- islandr already has the matching private key. Nullable, no
-- default needed (unlike V46's mistake: no NOT NULL here at all).

ALTER TABLE settings ADD COLUMN pending_csr_pem TEXT;
ALTER TABLE settings ADD COLUMN pending_key_pem TEXT;
ALTER TABLE settings ADD COLUMN pending_csr_created_at TIMESTAMP;
