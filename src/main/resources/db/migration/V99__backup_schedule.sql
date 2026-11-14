-- backup-schedule (1.4.0): automatic backups, and what the last run did.
-- schedule: 'off' | 'daily' | 'weekly'. schedule_since stops a schedule that is
-- switched on at 15:00 from "catching up" the 03:00 slot it never had.
ALTER TABLE backup_config ADD COLUMN schedule VARCHAR(8) NOT NULL DEFAULT 'off';
ALTER TABLE backup_config ADD COLUMN schedule_time VARCHAR(5) NOT NULL DEFAULT '03:00';
ALTER TABLE backup_config ADD COLUMN schedule_day INTEGER NOT NULL DEFAULT 1;
ALTER TABLE backup_config ADD COLUMN schedule_since TIMESTAMP;
ALTER TABLE backup_config ADD COLUMN last_run_at TIMESTAMP;
ALTER TABLE backup_config ADD COLUMN last_run_ok INTEGER;
ALTER TABLE backup_config ADD COLUMN last_run_message VARCHAR(1024);
