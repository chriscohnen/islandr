-- V58: optional per-site local DNS server IP (issue #45) — used by device
-- discovery to send a targeted reverse-DNS (PTR) query for a suggested
-- resource name (e.g. against a home router like a FRITZ!Box, which
-- usually knows DHCP-registered hostnames the JVM's system resolver has no
-- route/config to ask for a remote site reached over the tunnel). Null =
-- current behavior unchanged: discovery falls back to the system resolver.
ALTER TABLE sites ADD COLUMN dns_server_ip VARCHAR(45);
