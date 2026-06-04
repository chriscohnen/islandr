ALTER TABLE settings ADD COLUMN firewall_dry_run INTEGER NOT NULL DEFAULT 1;
UPDATE settings SET firewall_dry_run = 1;
