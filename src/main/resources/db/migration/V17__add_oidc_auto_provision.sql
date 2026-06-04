ALTER TABLE settings ADD COLUMN oidc_auto_provision INTEGER NOT NULL DEFAULT 1;
UPDATE settings SET oidc_auto_provision = 1;
