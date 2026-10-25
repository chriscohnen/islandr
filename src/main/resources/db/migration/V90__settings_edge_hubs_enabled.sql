-- V90: settings-edge-hubs-toggle — whether this installation uses edge hubs at
-- all. Default 0: one hub is the normal case, and for those installs every
-- mention of an edge is noise (a menu entry leading to an empty page, a
-- selector with one option).
--
-- A stored flag rather than deriving it from "does any edge_hubs row exist":
-- with the menu hidden while none exist, there would be no way to reach the
-- page that creates the first one. Switching it off again is refused while
-- edge hubs are still configured (EdgeHubService/SettingsResource).
ALTER TABLE settings ADD COLUMN edge_hubs_enabled INTEGER NOT NULL DEFAULT 0;

-- An install that already has edge hubs must not be locked out of them by its
-- own upgrade: with the flag off the menu entry disappears, and the operator
-- would have no way to reach the page that would tell them why. The default
-- above is for new installs; this line is for existing ones.
UPDATE settings SET edge_hubs_enabled = 1 WHERE EXISTS (SELECT 1 FROM edge_hubs);
