package de.chriscohnen.islandr.wg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the short-lived preshared-key file goes (psk-apparmor-path).
 *
 * <p>{@code wg} never accepts a preshared key as an argument — argv is world
 * readable, so it only reads one from a file. Ubuntu 26.04 ships an enforcing
 * AppArmor profile for {@code /usr/bin/wg} whose single file rule is
 * {@code file rw @{etc_rw}/wireguard/{,**}}, and which denies the binary
 * {@code dac_override} and {@code dac_read_search}. Measured on the image: a
 * key under {@code /tmp}, {@code /var/tmp}, {@code /run} or {@code /root} is
 * refused even for root, with no error beyond "fopen: Permission denied".
 *
 * <p>So the file has to be born under {@code /etc/wireguard}. These tests pin
 * the two things that must hold for that to be safe and not break an existing
 * install:
 *
 * <ol>
 *   <li>the key is written to the configured directory, readable by nobody but
 *       its owner;</li>
 *   <li>a directory that is missing or not writable — every host upgraded from
 *       a version before this change — falls back instead of failing, because
 *       the old location still works everywhere except 26.04.</li>
 * </ol>
 */
class PskFileLocationTest {

    private static final String PSK = "aGVsbG8gdGhlcmUgdGhpcyBpcyAzMiBieXRlcyE=";

    @Test
    void writesTheKeyIntoTheConfiguredDirectory(@TempDir Path dir) throws Exception {
        RealWgAdapter adapter = new RealWgAdapter(false, dir.toString());

        Path written = adapter.createPskFile(PSK);
        try {
            assertThat(written.getParent()).isEqualTo(dir);
            assertThat(Files.readString(written)).isEqualTo(PSK);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    /**
     * Owner read-write, group read, nothing for anyone else.
     *
     * <p>Group read is not a loosening, it is what makes the file readable at
     * all. The AppArmor profile denies {@code wg} {@code dac_override}, so root
     * reads by the ordinary permission bits like any other account — and a
     * 0600 file owned by the service user is closed to it (measured on the
     * image: refused). The installer makes the directory setgid {@code root},
     * so the file is born in group {@code root}, and group read lets root's
     * {@code wg} in through the group. Nobody outside group root gets anything.
     */
    @Test
    void theKeyIsReadableByItsOwnerAndItsGroupOnly(@TempDir Path dir) throws Exception {
        RealWgAdapter adapter = new RealWgAdapter(false, dir.toString());

        Path written = adapter.createPskFile(PSK);
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(written);
            assertThat(perms).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    /** The upgrade path: 1.1.0 never created the directory, and the service
     *  user cannot create it under /etc itself. Falling back keeps those hosts
     *  working — they are not on 26.04, or they would not have worked before
     *  either. */
    @Test
    void fallsBackWhenTheConfiguredDirectoryIsNotThere(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("not-created-by-any-installer");
        RealWgAdapter adapter = new RealWgAdapter(false, missing.toString());

        Path written = adapter.createPskFile(PSK);
        try {
            assertThat(written.getParent()).isNotEqualTo(missing);
            assertThat(Files.readString(written)).isEqualTo(PSK);
            assertThat(Files.getPosixFilePermissions(written)).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    /** The warning about the fallback is logged once per process; the
     *  fallback itself has to keep working every time after that. */
    @Test
    void theFallbackKeepsWorkingAfterTheFirstTime(@TempDir Path dir) throws Exception {
        RealWgAdapter adapter = new RealWgAdapter(false, dir.resolve("missing").toString());

        for (int i = 0; i < 3; i++) {
            Path written = adapter.createPskFile(PSK);
            try {
                assertThat(Files.readString(written)).isEqualTo(PSK);
            } finally {
                Files.deleteIfExists(written);
            }
        }
    }

    /**
     * The directory is on disk under /etc, not in a private /tmp or on a
     * tmpfs: a process killed between writing a key and deleting it would
     * leave that key there for good — and /etc is what config backups and
     * etckeeper capture. A key file lives for one wg call (bounded at five
     * seconds), so anything older than a minute is a leftover, never one in
     * use.
     */
    @Test
    void aSweepRemovesLeftoverKeysAndNothingElse(@TempDir Path dir) throws Exception {
        Path leftover = Files.writeString(dir.resolve("psk-123.key"), PSK);
        Files.setLastModifiedTime(leftover,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(120)));
        Path inUse = Files.writeString(dir.resolve("psk-456.key"), PSK);
        Path notOurs = Files.writeString(dir.resolve("operator-notes.txt"), "keep me");
        Files.setLastModifiedTime(notOurs,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));

        new RealWgAdapter(false, dir.toString()).sweepStalePskFiles();

        assertThat(leftover).doesNotExist();
        assertThat(inUse).exists();
        assertThat(notOurs).exists();
    }

    @Test
    void aSweepOfAMissingDirectoryIsANoOp(@TempDir Path dir) {
        new RealWgAdapter(false, dir.resolve("missing").toString()).sweepStalePskFiles();
        new RealWgAdapter(false, "").sweepStalePskFiles();
    }

    /** An empty configured directory means "wherever the JVM puts temp files",
     *  which is what every release before this one did. */
    @Test
    void anEmptySettingMeansTheOldBehaviour() throws Exception {
        RealWgAdapter adapter = new RealWgAdapter(false, "");

        Path written = adapter.createPskFile(PSK);
        try {
            assertThat(Files.readString(written)).isEqualTo(PSK);
        } finally {
            Files.deleteIfExists(written);
        }
    }
}
