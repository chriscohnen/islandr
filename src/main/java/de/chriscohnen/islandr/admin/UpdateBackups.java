package de.chriscohnen.islandr.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Whether a rollback actually exists.
 *
 * <p>The console tells an admin how to update and names {@code --rollback}
 * beside it. What it could not tell them is whether there is anything to roll
 * back to — that only became apparent in the moment it was needed, which is the
 * worst moment to find out. {@code update.sh} writes two files before it swaps
 * anything: the running binary as {@code islandr.prev}, and a hot
 * {@code sqlite3 .backup} of the database as {@code <db>.prev}.
 *
 * <p><b>Both, or neither.</b> Restoring only the binary is not a rollback:
 * migrations run at startup and there are no undo migrations, so a version that
 * migrated and then failed leaves a schema the older binary refuses. A hub with
 * one half present has no usable rollback, and {@link Status#present()} says so.
 *
 * <p><b>Reading only.</b> This needs no privilege beyond what the service
 * already has — the files are readable, and nothing here writes, executes or
 * restores. Triggering a rollback is a different question with a different cost
 * (it would need root, see ADR-0011) and is deliberately not answered here.
 *
 * <p><b>What it does not claim:</b> which version is inside the backup. That
 * cannot be read from the file without running it, so the report carries the
 * timestamp and the size and stops there. A guessed version number in a
 * recovery path is worse than none.
 */
public final class UpdateBackups {

    /** @param modifiedAt null when the file is absent — not epoch zero, which
     *                    would render as a real date from 1970. */
    public record File(boolean present, long sizeBytes, Instant modifiedAt) {
        static final File ABSENT = new File(false, 0L, null);
    }

    /** @param present true only when *both* halves are there — see the class
     *                 note on why half a backup is not a backup. */
    public record Status(boolean present, File binary, File database) {}

    private UpdateBackups() {}

    public static Status inspect(Path binaryBackup, Path databaseBackup) {
        File b = stat(binaryBackup);
        File d = stat(databaseBackup);
        return new Status(b.present() && d.present(), b, d);
    }

    /**
     * The database backup sits beside the database itself, with {@code .prev}
     * appended — the naming {@code update.sh} uses.
     *
     * @return null for anything that is not a SQLite file URL. PostgreSQL has
     *         no file for {@code update.sh} to copy, so there is nothing to
     *         report rather than something to report as missing.
     */
    public static Path databaseBackupFrom(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) return null;
        String file = jdbcUrl.substring("jdbc:sqlite:".length());
        if (file.isBlank() || file.startsWith("file:")) return null;  // in-memory, tests
        try {
            return Path.of(file + ".prev");
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static File stat(Path p) {
        if (p == null) return File.ABSENT;
        try {
            if (!Files.isRegularFile(p)) return File.ABSENT;
            return new File(true, Files.size(p), Files.getLastModifiedTime(p).toInstant());
        } catch (IOException | SecurityException e) {
            // Unreadable is indistinguishable from absent for this purpose, and
            // claiming a rollback exists when it cannot be read is the worse error.
            return File.ABSENT;
        }
    }
}
