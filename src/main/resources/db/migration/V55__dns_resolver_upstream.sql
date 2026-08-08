-- V55: separate upstream-forwarding field for the DNS resolver (ADR-0023).
--
-- wgClientDns is what a client writes into its own DNS = line and keeps its
-- original meaning always (including split-DNS "~domain" syntax, which is
-- meaningless as a forward target). dns_resolver_upstream is the resolver's
-- own, independent concern: where it forwards queries outside the managed
-- zone. Null/blank falls back to a hardcoded default (1.1.1.1, 8.8.8.8) in
-- DnsQueryHandler, not a silent forwarding blackhole.
ALTER TABLE settings ADD COLUMN dns_resolver_upstream VARCHAR(255);
