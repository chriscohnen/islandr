package de.chriscohnen.islandr.firewall;

import org.jboss.logging.Logger;

/**
 * In-memory NftablesAdapter for dev/test/macOS. Records the last applied
 * ruleset string in a field so tests can assert what would have hit the
 * kernel. Validation always succeeds — there is no nft binary to disagree.
 *
 * <p>If a test needs to simulate a validation failure, it can set
 * {@link #forceFailure}; the next validate/apply pair will return that
 * stderr verbatim. {@link #resetForTests} clears it back to OK.
 */
public class MockNftablesAdapter implements NftablesAdapter {

    private static final Logger LOG = Logger.getLogger(MockNftablesAdapter.class);

    public volatile String lastApplied;
    public volatile int applyCount;
    public volatile String forceFailure;

    @Override
    public ValidationResult validate(String rulesetText) {
        if (forceFailure != null) return ValidationResult.fail(forceFailure);
        return ValidationResult.success();
    }

    @Override
    public void apply(String rulesetText) {
        if (forceFailure != null) {
            throw new NftablesException("mock validation failed: " + forceFailure);
        }
        lastApplied = rulesetText;
        applyCount++;
        LOG.debugf("MockNftablesAdapter applied %d-byte ruleset (#%d)",
                rulesetText.length(), applyCount);
    }

    @Override
    public void resetForTests() {
        lastApplied = null;
        applyCount = 0;
        forceFailure = null;
    }
}
