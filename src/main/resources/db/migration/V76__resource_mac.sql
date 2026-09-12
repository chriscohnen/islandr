-- V76: MAC address on a Resource (issue #76). Discovered via the kernel's
-- own ARP cache (unprivileged — ADR-0011/0014's existing posture) during a
-- scan, or set via the on-demand /identify action on an already-registered
-- resource. Vendor is NEVER stored here — always derived from the bundled
-- OUI table at read time (OuiVendorLookup), so refreshing that table later
-- never leaves a stale vendor name behind.
ALTER TABLE resources ADD COLUMN mac VARCHAR(17) NULL;
