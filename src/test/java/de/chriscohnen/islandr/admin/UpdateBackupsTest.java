package de.chriscohnen.islandr.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console tells an admin how to update and names {@code --rollback}. What
 * it could not tell them is whether a rollback exists at all — that only became
 * apparent in the moment it was needed. {@code update.sh} writes two files
 * before it swaps anything; this reports on them and nothing else.
 */
class UpdateBackupsTest {

    @Test
    void reportsBothBackupsWhenTheyExist(@TempDir Path dir) throws Exception {
        Path binary = Files.writeString(dir.resolve("islandr.prev"), "x".repeat(64));
        Path db = Files.writeString(dir.resolve("islandr.db.prev"), "y".repeat(128));

        UpdateBackups.Status s = UpdateBackups.inspect(binary, db);

        assertThat(s.present()).isTrue();
        assertThat(s.binary().present()).isTrue();
        assertThat(s.binary().sizeBytes()).isEqualTo(64);
        assertThat(s.binary().modifiedAt()).isNotNull();
        assertThat(s.database().sizeBytes()).isEqualTo(128);
    }

    /**
     * A hub that has never been updated has no backup, and that is a normal
     * state — not an error. The distinction matters because the console says
     * one thing in each case and must not say "rollback available" in this one.
     */
    @Test
    void aHubThatWasNeverUpdatedHasNoBackup(@TempDir Path dir) {
        UpdateBackups.Status s = UpdateBackups.inspect(
                dir.resolve("islandr.prev"), dir.resolve("islandr.db.prev"));

        assertThat(s.present()).isFalse();
        assertThat(s.binary().present()).isFalse();
        assertThat(s.binary().sizeBytes()).isZero();
        assertThat(s.binary().modifiedAt()).isNull();
    }

    /**
     * Half a backup is not a backup. Restoring only the binary leaves a schema
     * the older version refuses to validate, so the console must not present
     * this as a usable rollback.
     */
    @Test
    void oneHalfMissingMeansNoUsableRollback(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("islandr.prev"), "x");

        UpdateBackups.Status s = UpdateBackups.inspect(
                dir.resolve("islandr.prev"), dir.resolve("islandr.db.prev"));

        assertThat(s.binary().present()).isTrue();
        assertThat(s.database().present()).isFalse();
        assertThat(s.present()).isFalse();
    }

    /** Callers pass paths derived from configuration; nonsense must not throw. */
    @Test
    void unreadableOrNullPathsAreReportedAsAbsent() {
        UpdateBackups.Status s = UpdateBackups.inspect(null, null);

        assertThat(s.present()).isFalse();
        assertThat(s.binary().present()).isFalse();
        assertThat(s.database().present()).isFalse();
    }

    /** The database backup sits beside the database file, named `.prev`. */
    @Test
    void derivesTheDatabaseBackupPathFromTheJdbcUrl() {
        assertThat(UpdateBackups.databaseBackupFrom("jdbc:sqlite:/var/lib/islandr/data/islandr.db"))
                .isEqualTo(Path.of("/var/lib/islandr/data/islandr.db.prev"));
        assertThat(UpdateBackups.databaseBackupFrom("jdbc:postgresql://host/db")).isNull();
        assertThat(UpdateBackups.databaseBackupFrom("")).isNull();
        assertThat(UpdateBackups.databaseBackupFrom(null)).isNull();
    }
}
