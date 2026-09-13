-- V77: which addresses may speak for a client, and in which header (issue #80).
-- Runtime settings rather than application.properties, because a reverse proxy
-- is an operational fact like TLS or the DNS resolver, not a bootstrap one —
-- and because getting it wrong is worth fixing without a service restart.
--
-- NULL / empty is the safe default and must stay so: a forwarded header is
-- something anyone can send, so an unproxied hub must not be able to be fooled
-- by one at all. Only requests arriving from an address named here may claim
-- to speak for someone else.
ALTER TABLE settings ADD COLUMN trusted_proxies TEXT NULL;
ALTER TABLE settings ADD COLUMN client_ip_header VARCHAR(64) NULL;
