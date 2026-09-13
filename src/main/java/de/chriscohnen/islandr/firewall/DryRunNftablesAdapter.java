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

    /**
     * With firewall writes paused, Islandr applies nothing — so removing the
     * boot table would open the hub on the strength of a setting whose whole
     * purpose is that nothing is enforced yet (ADR-0031).
     */
    @Override
    public BootTableHandover removeBootTable() {
        if (settings.get().firewallDryRun) {
            LOG.info("[dry-run] boot firewall table kept — nothing is being enforced yet");
            return BootTableHandover.KEPT_DRY_RUN;
        }
        return delegate.removeBootTable();
    }
}
