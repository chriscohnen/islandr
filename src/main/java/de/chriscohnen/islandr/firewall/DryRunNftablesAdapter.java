package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.settings.SettingsService;
import org.jboss.logging.Logger;

/**
 * Wraps a real NftablesAdapter and suppresses the {@link #apply} call when
 * {@code settings.firewallDryRun} is true. Validation always runs so the
 * admin can still see whether the generated ruleset is syntactically correct.
 */
class DryRunNftablesAdapter implements NftablesAdapter {

    private static final Logger LOG = Logger.getLogger(DryRunNftablesAdapter.class);

    private final NftablesAdapter delegate;
    private final SettingsService settings;

    DryRunNftablesAdapter(NftablesAdapter delegate, SettingsService settings) {
        this.delegate = delegate;
        this.settings = settings;
    }

    @Override
    public ValidationResult validate(String rulesetText) {
        return delegate.validate(rulesetText);
    }

    @Override
    public void apply(String rulesetText) {
        if (settings.get().firewallDryRun) {
            LOG.info("[dry-run] nftables apply skipped — firewall writes are paused");
            return;
        }
        delegate.apply(rulesetText);
    }
}
