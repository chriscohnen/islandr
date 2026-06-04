ALTER TABLE settings ADD COLUMN firewall_dry_run INTEGER NOT NULL DEFAULT 0;
UPDATE settings SET firewall_dry_run = 0;
