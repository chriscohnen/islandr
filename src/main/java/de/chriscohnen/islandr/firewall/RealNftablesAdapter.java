package de.chriscohnen.islandr.firewall;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to the {@code nft} CLI. Two operations:
 * <ol>
 *   <li>{@link #validate} writes the candidate ruleset to a temp file and
 *       runs {@code nft -c -f <tempfile>}. Returns the exit code +
 *       captured stderr.</li>
 *   <li>{@link #apply} validates again (defence-in-depth) and on success
 *       runs {@code nft -f <tempfile>}. nftables guarantees atomic
 *       replacement of the named table.</li>
 * </ol>
 *
 * <p><b>Safety invariant:</b> the ruleset string always starts with
 * {@code flush table inet islandr} and never contains {@code flush ruleset}
 * or table definitions other than {@code islandr}. {@link RuleBuilder} is
 * responsible for emitting that shape; this adapter just runs whatever it's
 * handed, so the integrity of the constraint lives in the generator.
 *
 * <p>The {@code nft} binary needs {@code CAP_NET_ADMIN}. The systemd unit
 * documented in {@code docs/install.md} grants it via {@code AmbientCapabilities}.
 */
public class RealNftablesAdapter implements NftablesAdapter {

    private static final Logger LOG = Logger.getLogger(RealNftablesAdapter.class);
    private static final long PROCESS_TIMEOUT_SECONDS = 10;

    /** The fail-closed table installed by setup-hub.sh's oneshot unit (ADR-0031). */
    static final String BOOT_TABLE = "islandr-boot";

    private final boolean useSudo;
    private final Path dataDir;

    public RealNftablesAdapter(boolean useSudo, Path dataDir) {
        this.useSudo = useSudo;
        this.dataDir = dataDir;
    }

    @Override
    public ValidationResult validate(String rulesetText) {
        try {
            Path tmp = stage(rulesetText);
            try {
                ProcessResult r = run(List.of("nft", "-c", "-f", tmp.toString()));
                if (r.exitCode == 0) return ValidationResult.success();
                LOG.warnf("nft -c rejected ruleset: %s", r.stderr);
                return ValidationResult.fail(r.stderr);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return ValidationResult.fail("could not run nft: " + ex.getMessage());
        }
    }

    @Override
    public void apply(String rulesetText) {
        try {
            Path tmp = stage(rulesetText);
            try {
                // Defence-in-depth: validate again right before apply. Same
                // file, same nft binary — guards against the rare case where
                // someone calls apply() without a prior validate() (e.g.
                // boot-time bootstrap), and against TOCTOU on the temp file.
                ProcessResult check = run(List.of("nft", "-c", "-f", tmp.toString()));
                if (check.exitCode != 0) {
                    throw new NftablesException("pre-apply validation failed: " + check.stderr);
                }
                ProcessResult applyResult = run(List.of("nft", "-f", tmp.toString()));
                if (applyResult.exitCode != 0) {
                    // nftables holds the previous table on failure — we don't
                    // need to roll back, just surface the error.
                    throw new NftablesException("nft -f failed: " + applyResult.stderr);
                }
                LOG.infof("nftables applied (%d bytes)", rulesetText.length());
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new NftablesException("could not run nft: " + ex.getMessage(), ex);
        }
    }

    /**
     * ADR-0031: the boot table is what keeps the hub closed between the reboot
     * and Islandr enforcing. It goes away only here, after this adapter has
     * successfully applied Islandr's own table.
     *
     * <p>{@code nft delete table} on a table that does not exist is an error,
     * not a no-op, so existence is checked first — an upgraded install without
     * the unit must report ABSENT, not FAILED.
     */
    @Override
    public BootTableHandover removeBootTable() {
        try {
            ProcessResult exists = run(List.of("nft", "list", "table", "inet", BOOT_TABLE));
            if (exists.exitCode != 0) return BootTableHandover.ABSENT;

            ProcessResult del = run(List.of("nft", "delete", "table", "inet", BOOT_TABLE));
            if (del.exitCode == 0) {
                LOG.infof("boot firewall table 'inet %s' removed — islandr's own ruleset is live", BOOT_TABLE);
                return BootTableHandover.REMOVED;
            }
            LOG.errorf("could not remove boot firewall table 'inet %s': %s — "
                    + "both tables are live and a drop in either wins, so granted traffic stays blocked",
                    BOOT_TABLE, del.stderr);
            return BootTableHandover.FAILED;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.errorf(ex, "could not run nft to remove the boot firewall table");
            return BootTableHandover.FAILED;
        }
    }

    private Path stage(String rulesetText) throws IOException {
        // Write to dataDir so the path is covered by the sudoers NOPASSWD rule.
        // /tmp is not allowed by the scoped sudo grant (see docs/adr/0011).
        Path tmp = Files.createTempFile(dataDir, "islandr-nft-", ".nft");
        Files.writeString(tmp, rulesetText, StandardCharsets.UTF_8);
        return tmp;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    private ProcessResult run(List<String> argv) throws IOException, InterruptedException {
        List<String> effective;
        if (useSudo) {
            effective = new ArrayList<>(argv.size() + 1);
            effective.add("sudo");
            effective.addAll(argv);
        } else {
            effective = argv;
        }
        Process p = new ProcessBuilder(effective).redirectErrorStream(false).start();
        boolean finished = p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("nft did not finish within " + PROCESS_TIMEOUT_SECONDS + "s");
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(p.exitValue(), stdout, stderr);
    }
}
