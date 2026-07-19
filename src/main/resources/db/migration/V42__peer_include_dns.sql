-- Per-peer DNS opt-out (issue #29). The global DNS line (settings.wg_client_dns)
-- is written into every client .conf/QR today. Some handoffs (a phone scanning
-- the QR directly) don't want tunneled DNS. This flag lets an admin suppress
-- the DNS line for one peer without touching the global setting.
-- true (default) = include the DNS line when a global DNS is configured (today's
-- behaviour, unchanged for every existing peer). false = never write it for this
-- peer, even if a global DNS is set.
ALTER TABLE peers ADD COLUMN include_dns BOOLEAN NOT NULL DEFAULT TRUE;
