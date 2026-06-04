package de.chriscohnen.islandr.wg;

import de.chriscohnen.islandr.settings.SettingsService;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Wraps a real WgAdapter and suppresses all write operations when
 * {@code settings.firewallDryRun} is true. Read operations (genKeypair,
 * derivePublicKey, showPeers) always delegate to the real adapter.
 *
 * <p>This lets an admin pause WireGuard writes at runtime via the Settings UI
 * without restarting the service — useful when configuring on a dev machine
 * before moving the config to a real hub.
 */
class DryRunWgAdapter implements WgAdapter {

    private static final Logger LOG = Logger.getLogger(DryRunWgAdapter.class);

    private final WgAdapter delegate;
    private final SettingsService settings;

    DryRunWgAdapter(WgAdapter delegate, SettingsService settings) {
        this.delegate = delegate;
        this.settings = settings;
    }

    private boolean dryRun() {
        return settings.get().firewallDryRun;
    }

    @Override public Keypair genKeypair()                         { return delegate.genKeypair(); }
    @Override public String derivePublicKey(String privateKey)    { return delegate.derivePublicKey(privateKey); }
    @Override public List<PeerStatus> showPeers(String iface)     { return delegate.showPeers(iface); }

    @Override
    public void setPeer(String iface, String publicKey, String allowedIps) {
        if (dryRun()) { LOG.infof("[dry-run] wg setPeer skipped for %s on %s", publicKey, iface); return; }
        delegate.setPeer(iface, publicKey, allowedIps);
    }

    @Override
    public void removePeer(String iface, String publicKey) {
        if (dryRun()) { LOG.infof("[dry-run] wg removePeer skipped for %s on %s", publicKey, iface); return; }
        delegate.removePeer(iface, publicKey);
    }
}
