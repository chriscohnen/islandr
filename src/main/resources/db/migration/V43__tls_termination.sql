-- V43: built-in TLS termination (ADR-0015, issue #22).
-- "none" (default) = the baked-in dummy placeholder cert is in effect until an
-- admin uploads real material. "managed" = tls_cert_pem/tls_key_pem hold the
-- uploaded certificate (key encrypted at rest when EncryptionService is
-- configured). "referenced" = tls_cert_path/tls_key_path point at a file pair
-- islandr does not own or copy.
ALTER TABLE settings ADD COLUMN tls_mode      VARCHAR(20) NOT NULL DEFAULT 'none';
ALTER TABLE settings ADD COLUMN tls_cert_pem  TEXT;
ALTER TABLE settings ADD COLUMN tls_key_pem   TEXT;
ALTER TABLE settings ADD COLUMN tls_cert_path VARCHAR(512);
ALTER TABLE settings ADD COLUMN tls_key_path  VARCHAR(512);
