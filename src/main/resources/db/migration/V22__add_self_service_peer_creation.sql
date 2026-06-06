ALTER TABLE settings ADD COLUMN self_service_peer_creation INTEGER NOT NULL DEFAULT 1;
UPDATE settings SET self_service_peer_creation = 1;
