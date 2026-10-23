package de.chriscohnen.islandr.firewall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the ruleset is staged before {@code sudo nft -f} (sudo-rs-nft-path).
 *
 * <p>The scoped sudoers grant (ADR-0011) names the file {@code nft} may read.
 * Until 1.2.0 that was a pattern, {@code islandr-nft-*.nft}, matching a fresh
 * temp file per call. Ubuntu 26.04 replaces sudo with sudo-rs, whose visudo
 * refuses a pattern in a command argument — so on 26.04 setup-hub.sh aborted
 * at the sudoers step. Measured on the runner images:
 *
 * <ul>
 *   <li>sudo-rs accepts an exact path, and only that path;</li>
 *   <li>classic sudo's old pattern also matches a fixed name inside it.</li>
 * </ul>
 *
 * So the adapter stages to one fixed file whose name fits the old pattern:
 * a hub updated in place keeps working with the sudoers it already has, and
 * a 26.04 hub gets an exact line sudo-rs takes.
 */
class NftStagingFileTest {

    @Test
    void theStagingFileHasOneFixedName(@TempDir Path dir) {
        RealNftablesAdapter a = new RealNftablesAdapter(false, dir);
        assertThat(a.stagingFile()).isEqualTo(dir.resolve("islandr-nft-current.nft"));
    }

    /** The upgrade path: every hub set up before 1.2.0 has sudoers lines
     *  with this pattern, and update.sh does not touch them. */
    @Test
    void theFixedNameStillMatchesTheSudoersPatternOfEarlierReleases(@TempDir Path dir) {
        var legacyGrant = FileSystems.getDefault().getPathMatcher("glob:islandr-nft-*.nft");
        Path staged = new RealNftablesAdapter(false, dir).stagingFile();
        assertThat(legacyGrant.matches(staged.getFileName())).isTrue();
    }

    @Test
    void stagingWritesTheRuleset(@TempDir Path dir) throws Exception {
        RealNftablesAdapter a = new RealNftablesAdapter(false, dir);
        Path staged = a.stage("flush table inet islandr\n");
        assertThat(Files.readString(staged)).isEqualTo("flush table inet islandr\n");
    }

    /** A leftover from a killed process is replaced, not appended to. */
    @Test
    void stagingReplacesALeftover(@TempDir Path dir) throws Exception {
        RealNftablesAdapter a = new RealNftablesAdapter(false, dir);
        Files.writeString(a.stagingFile(), "something much longer that must not survive\n");
        a.stage("short\n");
        assertThat(Files.readString(a.stagingFile())).isEqualTo("short\n");
    }

    /**
     * With a fixed name, root's nft reads whatever sits at that path. A
     * symlink planted there must be removed, not written through — the old
     * per-call temp file got this for free from its exclusive create.
     */
    @Test
    void stagingDoesNotWriteThroughASymlink(@TempDir Path dir) throws Exception {
        Path elsewhere = Files.writeString(dir.resolve("not-the-ruleset.txt"), "untouched\n");
        RealNftablesAdapter a = new RealNftablesAdapter(false, dir);
        Files.createSymbolicLink(a.stagingFile(), elsewhere);

        a.stage("flush table inet islandr\n");

        assertThat(Files.readString(elsewhere)).isEqualTo("untouched\n");
        assertThat(Files.isSymbolicLink(a.stagingFile())).isFalse();
        assertThat(Files.readString(a.stagingFile())).isEqualTo("flush table inet islandr\n");
    }

    /** When sudo refuses, its own message names neither the sudoers line
     *  nor the fix. This message reaches the admin's screen. */
    @Test
    void aSudoRefusalIsExplained() {
        String sudo = "sudo: a terminal is required to read the password\nsudo: a password is required";
        assertThat(RealNftablesAdapter.explain(sudo))
                .startsWith(sudo)
                .contains("/etc/sudoers.d/islandr")
                .contains("sudo-rs");
        String sudoRs = "sudo-rs: I'm sorry islandr. I'm afraid I can't do that";
        assertThat(RealNftablesAdapter.explain(sudoRs)).contains("/etc/sudoers.d/islandr");
    }

    @Test
    void anNftErrorIsPassedThroughUntouched() {
        String nft = "/var/lib/islandr/islandr-nft-current.nft:3:5-9: Error: syntax error";
        assertThat(RealNftablesAdapter.explain(nft)).isEqualTo(nft);
    }
}
