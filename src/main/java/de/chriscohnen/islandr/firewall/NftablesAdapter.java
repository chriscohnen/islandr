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

    /** Tells the adapter to behave as if no apply has ever happened. Used by tests. */
    default void resetForTests() { /* no-op */ }
}
