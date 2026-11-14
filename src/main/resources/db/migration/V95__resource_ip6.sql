-- V95: optional IPv6 address next to the resource's main address, so one name can
-- answer both A and AAAA (dual stack).
ALTER TABLE resources ADD COLUMN ip6 VARCHAR(45);
