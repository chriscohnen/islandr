-- V49: DNS-01 challenge support for ACME (ADR-0020), alongside the existing
-- HTTP-01 flow (ADR-0019) -- an alternative for hubs that don't want port 80
-- reachable from the internet. 'http-01' is a literal constant default, not
-- CURRENT_TIMESTAMP -- unlike V46's mistake, SQLite's ADD COLUMN accepts this.

ALTER TABLE settings ADD COLUMN acme_challenge_type VARCHAR(20) NOT NULL DEFAULT 'http-01';
ALTER TABLE settings ADD COLUMN acme_dns_provider VARCHAR(50);
ALTER TABLE settings ADD COLUMN acme_dns_api_token TEXT;

-- "manual" provider (no API automation): the challenge is computed, then
-- issuance pauses -- the admin adds the TXT record at whatever DNS host they
-- actually use, and clicks "Continue" once it's live. The ACME order/authz/
-- challenge/finalize URLs have to survive across those two separate admin
-- actions (and a possible restart in between), so they're persisted here
-- rather than kept only in memory.
ALTER TABLE settings ADD COLUMN acme_dns_pending_record_name VARCHAR(255);
ALTER TABLE settings ADD COLUMN acme_dns_pending_record_value VARCHAR(255);
ALTER TABLE settings ADD COLUMN acme_dns_pending_order_url VARCHAR(512);
ALTER TABLE settings ADD COLUMN acme_dns_pending_authz_url VARCHAR(512);
ALTER TABLE settings ADD COLUMN acme_dns_pending_challenge_url VARCHAR(512);
ALTER TABLE settings ADD COLUMN acme_dns_pending_finalize_url VARCHAR(512);
