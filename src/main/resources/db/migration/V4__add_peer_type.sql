-- V4: peer type + site-mode allowed CIDRs.
-- A "client" peer is a single device dialling in (laptop, phone).
-- A "site" peer is a WireGuard gateway that exposes a downstream network —
-- e.g. a Pi in a branch office sitting in front of file servers, RDP hosts.
-- The peer's own assigned_ip stays the same, but its AllowedIPs also list
-- one or more downstream CIDRs.
--
-- This is intentionally a thin column on the peers table. The proper Sites &
-- Resources domain (PRD §7, ADR-0006) will move site_allowed_cidrs into a
-- normalised sites table later; that migration will copy the values across.

ALTER TABLE peers ADD COLUMN type TEXT NOT NULL DEFAULT 'client'
    CHECK (type IN ('client', 'site'));

-- Comma-separated CIDR list, NULL for client peers. Validated server-side
-- (format, no overlap with WG subnet, no overlap with other site peers).
ALTER TABLE peers ADD COLUMN site_allowed_cidrs TEXT NULL;
