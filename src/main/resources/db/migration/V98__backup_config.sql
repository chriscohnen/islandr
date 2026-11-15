-- backup-s3 (1.4.0): where backups go and the key they are encrypted with.
-- One row (id = 1), like settings. The access/secret key and the backup key are
-- stored encrypted with ISLANDR_ENCRYPTION_KEY when one is configured.
CREATE TABLE backup_config (
    id          INTEGER      NOT NULL PRIMARY KEY,
    endpoint    VARCHAR(255),
    region      VARCHAR(63),
    bucket      VARCHAR(255),
    prefix      VARCHAR(255),
    access_key  VARCHAR(512),
    secret_key  VARCHAR(1024),
    path_style  INTEGER      NOT NULL DEFAULT 1,
    backup_key  VARCHAR(1024),
    updated_at  TIMESTAMP
);
