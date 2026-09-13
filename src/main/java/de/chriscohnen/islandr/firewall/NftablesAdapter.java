package de.chriscohnen.islandr.firewall;

/**
 * Thin wrapper around the {@code nft} CLI. Two operations: validate a
 * candidate ruleset and apply it atomically. Both take the full ruleset
 * text as a string; the adapter is responsible for staging it (typically a
 * temp file) and shelling out.
 *
 * <p>Two implementations live in this package: {@link MockNftablesAdapter}
 * for dev/test/macOS, {@link RealNftablesAdapter} for the Hub VM.
 * Which one CDI produces is decided by {@link NftablesAdapterProducer}
 * via the {@code islandr.nft.mode} config property.
 *
 * <p>Invariant — by contract: <strong>no implementation may call
 * {@code nft flush ruleset} or otherwise touch tables outside
 * {@code inet islandr}.</strong> The rule generator always emits a
 * {@code flush table inet islandr} at the top of its output so the table
 * is replaced atomically; everything else on the kernel ruleset is left
 * untouched.
 */
public interface NftablesAdapter {

    /** Result of validating (dry-run) a ruleset against {@code nft -c -f}. */
    record ValidationResult(boolean ok, String stderr) {
        public static ValidationResult success() { return new ValidationResult(true, null); }
        public static ValidationResult fail(String stderr) { return new ValidationResult(false, stderr); }
    }

    /**
     * Run {@code nft -c -f <tempfile>} on the candidate ruleset. Returns
     * a success result if nft accepts it, or a failure with the raw stderr
     * (the UI surfaces this on the firewall card).
     */
    ValidationResult validate(String rulesetText);

    /**
     * Apply the ruleset via {@code nft -f <tempfile>}. The caller is
     * expected to have validated first; this method also re-validates so a
     * direct apply (e.g. boot bootstrap) is still safe.
     *
     * @throws NftablesException if apply fails despite validation. The
     *         kernel is left running whatever it had before (nftables
     *         guarantees this).
     */
    void apply(String rulesetText);

    /**
     * Outcome of handing over from the boot-time table to Islandr's own
     * (ADR-0031). The distinction matters to the operator: {@link #FAILED}
     * means both tables are live and a drop in either wins, so every granted
     * flow stays blocked — a state that must never be silent.
     */
    enum BootTableHandover {
        /** The boot table was there and is gone; Islandr's table is the only one. */
        REMOVED,
        /** Nothing to hand over — no boot table installed, the usual case on an upgrade. */
        ABSENT,
        /** The boot table is still live alongside Islandr's. Everything stays blocked. */
        FAILED,
        /** Deliberately kept: firewall writes are paused, so nothing is enforcing yet. */
        KEPT_DRY_RUN,
        /** This runtime has no boot table to remove (mock, or the socket proxy). */
        UNSUPPORTED
    }

    /**
     * Removes {@code table inet islandr-boot} once Islandr's own table is live
     * (ADR-0031). Called only after a successful apply — never before, so
     * there is no moment with neither table.
     *
     * <p>Default: {@link BootTableHandover#UNSUPPORTED}. The boot table is
     * installed by {@code setup-hub.sh}, which is the native systemd install;
     * the socket-proxy runtime would need its own op for this and has no such
     * unit to begin with.
     */
    default BootTableHandover removeBootTable() {
        return BootTableHandover.UNSUPPORTED;
    }

    /** Tells the adapter to behave as if no apply has ever happened. Used by tests. */
    default void resetForTests() { /* no-op */ }
}
