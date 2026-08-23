-- V71: optional extra HTTP header for outgoing webhook authentication.
--
-- Some receivers (an internal automation endpoint, a reverse-proxy-gated
-- ingest, ...) expect their own API key/token header — Authorization,
-- X-API-Key, whatever the receiving side picked — rather than (or in
-- addition to) the X-Islandr-Signature HMAC this app already sends for
-- format=generic. v1 supports exactly one configurable header name/value
-- pair; that's what's actually been asked for so far, and it's additive —
-- a second/third header can be a follow-up table if ever needed, without
-- touching this column pair.
--
-- Both columns are nullable together: either neither is set (no extra
-- header sent) or both are (WebhookService enforces the pairing at the
-- application layer, same as it already does for format-specific fields).
-- extra_header_value is a credential like the webhook's own secret column —
-- never echoed back to the frontend after it's saved, only "is it set".
ALTER TABLE webhooks ADD COLUMN extra_header_name VARCHAR(255) NULL;
ALTER TABLE webhooks ADD COLUMN extra_header_value VARCHAR(2048) NULL;
