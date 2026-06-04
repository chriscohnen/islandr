-- V15: device type for client peers (laptop, desktop, mobile, tablet, server, other).
-- Purely cosmetic — used in the UI to show the right icon and label.
-- Site peers leave this column NULL.
ALTER TABLE peers ADD COLUMN device_type VARCHAR(16) NULL;
