package de.chriscohnen.islandr.backup;

import java.time.Instant;
import java.util.List;

/** backup-s3: request and response shapes. Secrets only ever travel inwards. */
public final class BackupDto {

    private BackupDto() {}

    /** What the settings page shows; never the secret key or the backup key. */
    public record Status(
            String endpoint, String region, String bucket, String prefix, boolean pathStyle,
            String accessKey, boolean secretKeySet,
            boolean backupKeySet,
            // false = the access/secret/backup key sit unencrypted in the database
            boolean secretsEncrypted,
            Instant updatedAt,
            // backup-schedule
            String schedule, String scheduleTime, int scheduleDay, String zone,
            Instant lastRunAt, Boolean lastRunOk, String lastRunMessage) {}

    public record ScheduleRequest(String schedule, String time, Integer dayOfWeek) {}

    /** Connection settings; a blank {@code secretKey} on save keeps the stored one. */
    public record ConnRequest(String endpoint, String region, String bucket, String prefix, Boolean pathStyle,
                              String accessKey, String secretKey) {}

    /** Shown once: the key the admin has to write down. */
    public record NewKey(String key) {}

    public record KeyRequest(Boolean replace) {}

    /**
     * A listing or restore request. {@code conn} is optional: left out, the
     * stored settings are used; given, it lets a freshly installed instance
     * reach a bucket it has never been told about (bootstrap).
     */
    public record ListRequest(ConnRequest conn) {}

    public record RestoreRequest(ConnRequest conn, String name, String key) {}

    public record Backup(String name, long size, Instant lastModified,
                         String host, String islandrVersion, Instant createdAt) {}

    public record Listing(List<Backup> backups) {}

    public record RunResult(String name, long size) {}
}
