-- V57: opt-out of the subdomain layer for an individual resource (ADR-0023
-- follow-up). When true, the resource resolves as "<dnsName>.<zone>" directly
-- instead of "<dnsName>.<site-subdomain>.<zone>" — for deployments that don't
-- want a per-network subdomain layer at all, or only for some resources.
-- Default false everywhere: no existing resource's DNS name changes shape
-- just from this migration running.
ALTER TABLE resources ADD COLUMN dns_flat INTEGER NOT NULL DEFAULT 0;
