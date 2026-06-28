ALTER TABLE resource_ports ADD COLUMN rdp_clipboard     INTEGER NOT NULL DEFAULT 1;
ALTER TABLE resource_ports ADD COLUMN rdp_file_transfer INTEGER NOT NULL DEFAULT 0;
ALTER TABLE resource_ports ADD COLUMN rdp_access_mode   VARCHAR(16) NOT NULL DEFAULT 'native';
