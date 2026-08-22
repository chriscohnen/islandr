-- Per-webhook delivery format (issue #68 follow-up) — 'generic' keeps the
-- existing HMAC-signed Islandr envelope; 'gotify' renders a Gotify-native
-- payload ({title, message, priority}) instead, since Gotify's push API
-- (POST {server}/message?token=...) doesn't understand our envelope shape
-- or check our HMAC header at all.
ALTER TABLE webhooks ADD COLUMN format VARCHAR(16) NOT NULL DEFAULT 'generic';
