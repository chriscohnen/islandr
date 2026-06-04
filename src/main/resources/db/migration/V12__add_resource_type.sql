-- Resource type — purely UI metadata at this stage (icon in the topology
-- diagram, search/filter in the admin console). Has no impact on ACL/firewall
-- generation; nftables sees only ip + port.
--
-- Allowed values match the icon set in /js/Icons.js. Adding a new type later
-- means: a new icon path in Icons.js, plus a new CHECK constraint value here
-- via another migration. Keeping the set closed (CHECK) rather than free-form
-- avoids typos that break the icon lookup.
ALTER TABLE resources ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'computer'
    CHECK (type IN ('computer','printer','nas','switch'));
