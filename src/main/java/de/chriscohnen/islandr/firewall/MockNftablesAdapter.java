package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.proxy.ProxyUnavailableException;
import org.jboss.logging.Logger;

/**
 * In-memory NftablesAdapter for dev/test/macOS. Records the last applied
 * ruleset string in a field so tests can assert what would have hit the
 * kernel. Validation always succeeds — there is no nft binary to disagree.
 *
 * <p>If a test needs to simulate a validation failure, it can set
 * {@link #forceFailure}; the next validate/apply pair will return that
 * stderr verbatim. To simulate the socket proxy being unreachable, set
 * {@link #forceUnavailable}; validate/apply then throw
 * {@link ProxyUnavailableException} exactly as {@code SocketNftablesAdapter}
 * would. {@link #resetForTests} clears both back to OK.
 */
public class MockNftablesAdapter implements NftablesAdapter {

    private static final Logger LOG = Logger.getLogger(MockNftablesAdapter.class);

    public volatile String lastApplied;
    public volatile int applyCount;
    public volatile String forceFailure;
    public volatile boolean forceUnavailable;

    @Override
    public ValidationResult validate(String rulesetText) {
        if (forceUnavailable) throw new ProxyUnavailableException("mock: proxy unavailable");
        if (forceFailure != null) return ValidationResult.fail(forceFailure);
        return ValidationResult.success();
    }

    @Override
    public void apply(String rulesetText) {
        if (forceUnavailable) throw new ProxyUnavailableException("mock: proxy unavailable");
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
        forceUnavailable = false;
    }
}
