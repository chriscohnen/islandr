-- V45: ACME (Let's Encrypt) auto-provisioning for TLS (ADR-0019, issue #30).
-- tls_mode gains a third value, 'acme', alongside 'none'/'managed'/'referenced'
-- from V43 -- an ACME-obtained certificate is stored in the SAME tls_cert_pem/
-- tls_key_pem columns (identical PEM-in-DB shape as 'managed'), so no new
-- certificate-storage columns are needed. These columns hold ACME-specific
-- protocol/account state instead.
ALTER TABLE settings ADD COLUMN acme_domain          VARCHAR(255);
ALTER TABLE settings ADD COLUMN acme_account_key_pem TEXT;
-- Public half of the account keypair (X.509 SubjectPublicKeyInfo DER,
-- base64) stored alongside the private key so the JWK/thumbprint needed on
-- every ACME request can be rebuilt with a plain KeyFactory round-trip --
-- deliberately NOT re-derived from the private key via elliptic-curve point
-- multiplication, which would mean hand-rolled EC math beyond DER framing
-- (see ADR-0019 R-165: hand-rolling is scoped to encoding, not cryptography).
ALTER TABLE settings ADD COLUMN acme_account_pub_key VARCHAR(200);
ALTER TABLE settings ADD COLUMN acme_account_url     VARCHAR(512);
ALTER TABLE settings ADD COLUMN acme_last_attempt_at TIMESTAMP;
ALTER TABLE settings ADD COLUMN acme_last_renewal_at TIMESTAMP;
ALTER TABLE settings ADD COLUMN acme_last_error      TEXT;
