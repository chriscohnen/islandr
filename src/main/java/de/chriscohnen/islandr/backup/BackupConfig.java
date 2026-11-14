package de.chriscohnen.islandr.backup;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** backup-s3: the one row of backup settings. Secrets are stored as {@code EncryptionService} writes them. */
@Entity
@Table(name = "backup_config")
public class BackupConfig extends PanacheEntityBase {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    public int id = SINGLETON_ID;

    @Column(name = "endpoint") public String endpoint;
    @Column(name = "region") public String region;
    @Column(name = "bucket") public String bucket;
    /** Folder inside the bucket, with a trailing slash; empty = the bucket's top level. */
    @Column(name = "prefix") public String prefix;
    @Column(name = "access_key") public String accessKey;
    @Column(name = "secret_key") public String secretKey;
    @Column(name = "path_style", nullable = false, columnDefinition = "INTEGER") public boolean pathStyle = true;
    @Column(name = "backup_key") public String backupKey;
    @Column(name = "updated_at") public Instant updatedAt;

    /** backup-schedule: "off", "daily" or "weekly". */
    @Column(name = "schedule", nullable = false) public String schedule = "off";
    /** Local time of day, HH:mm, in the hub's own time zone. */
    @Column(name = "schedule_time", nullable = false) public String scheduleTime = "03:00";
    /** Day of week for "weekly", 1 = Monday … 7 = Sunday. */
    @Column(name = "schedule_day", nullable = false) public int scheduleDay = 1;
    @Column(name = "schedule_since") public Instant scheduleSince;
    @Column(name = "last_run_at") public Instant lastRunAt;
    @Column(name = "last_run_ok", columnDefinition = "INTEGER") public Boolean lastRunOk;
    @Column(name = "last_run_message") public String lastRunMessage;
}
