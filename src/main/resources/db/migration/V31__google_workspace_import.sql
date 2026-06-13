-- Google Workspace Directory import: service account JSON + impersonation email.
-- Both nullable — null means the feature is not configured.

ALTER TABLE settings ADD COLUMN google_ws_service_account_json TEXT;
ALTER TABLE settings ADD COLUMN google_ws_impersonation_email  VARCHAR(255);
