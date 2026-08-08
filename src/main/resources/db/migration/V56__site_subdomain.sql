-- V56: explicit, admin-editable DNS subdomain label per site/network (ADR-0023
-- follow-up). Null/blank keeps the current behavior: the DNS resolver derives
-- the label live from the site name (DnsQueryHandler.slugify) every query, so
-- this migration itself changes nothing for an install that doesn't opt in.
-- Once set, it's used verbatim instead of the derived slug — renaming the
-- site no longer silently changes every resource's DNS name.
ALTER TABLE sites ADD COLUMN subdomain VARCHAR(63);
