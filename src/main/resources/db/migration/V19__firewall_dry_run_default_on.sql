-- Change default for firewall_dry_run to 1 (enabled) so fresh installs
-- start in safe mode without writing to WireGuard or nftables.
-- Existing rows (already 0 from V18) are updated to 1 as well —
-- the operator must explicitly disable dry-run to activate the live firewall.
UPDATE settings SET firewall_dry_run = 1;
