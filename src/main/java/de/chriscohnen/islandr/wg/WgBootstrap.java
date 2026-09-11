package de.chriscohnen.islandr.wg;

import de.chriscohnen.islandr.peer.PeerService;
import de.chriscohnen.islandr.proxy.ProxyMode;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * On startup, probes the configured WireGuard interface directly (real/mock
 * mode — socket mode's equivalent boot probe is
 * {@link de.chriscohnen.islandr.proxy.ProxyReconciler#onStart}, which also
 * drives the enforcement banner). Nothing else surfaces this proactively: a
 * broken {@code wg}/interface setup would otherwise only show up as a
 * DEBUG-level line from the 30s activity poller — invisible at default log
 * levels, with no clear signal that WireGuard itself is the problem (#37).
 *
 * <p>Disable with {@code islandr.wg.boot-probe-enabled=false} (test profile
 * sets this so unit tests don't depend on wg being configured).
 *
 * <p>When the interface answers, every enabled peer is re-pushed
 * ({@code islandr.wg.boot-repush-enabled}). Peers set with {@code wg set} exist
 * in kernel state only — Islandr never writes {@code /etc/wireguard/<iface>.conf}
 * — so a host reboot or a {@code systemctl restart wg-quick@<iface>} brings the
 * interface back holding only the peers in that file. Verified on a live hub:
 * 12 peers before the restart, 3 after. Nothing else converges the interface
 * back to the DB in real mode (the activity poller only reads, PeerScheduleJob
 * acts on transitions rather than drift), so without this the peers stay gone
 * until an admin edits each one. Dry-run still suppresses the writes, so a
 * fresh install on an adopted hub changes nothing at boot.
 */
@ApplicationScoped
public class WgBootstrap {

    private static final Logger LOG = Logger.getLogger(WgBootstrap.class);

    @Inject WgAdapter wg;
    @Inject ProxyMode proxyMode;
    @Inject PeerService peers;
    @Inject SettingsService settingsSvc;

    @ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    @ConfigProperty(name = "islandr.wg.boot-probe-enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "islandr.wg.boot-repush-enabled", defaultValue = "true")
    boolean repushEnabled;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled || proxyMode.isSocket()) {
            return;
        }
        WgAdapter.ProbeResult probe = wg.probeServerDetailed(wgInterface);
        if (probe.reachable()) {
            LOG.infof("wg boot probe: interface '%s' reachable, %d peer(s)",
                    wgInterface, probe.info().peerCount());
            repushPeers();
        } else {
            // Expected on a correctly ordered boot: the unit starts before
            // wg-quick so the nftables table exists before the interface does
            // (docs/install.md §6). The activity poller pushes the peers once
            // the interface appears, so say that instead of only pointing at
            // the install guide — otherwise every clean boot logs what looks
            // like a broken WireGuard setup.
            LOG.warnf("wg boot probe: interface '%s' not reachable — %s. " +
                    "If the tunnel is starting after Islandr this is expected and the peers " +
                    "are applied on the next activity poll; otherwise check that WireGuard is " +
                    "installed and the interface is up (see docs/install.md).",
                    wgInterface, probe.error());
        }
    }

    /**
     * Converge the live interface back to DB state. Never fatal: a hub that
     * cannot re-push should still come up so the admin can log in and see why,
     * which is exactly what {@link PeerService#repushEnabledPeers()} is built
     * for — it skips a single broken peer instead of stranding the rest.
     */
    void repushPeers() {
        if (!repushEnabled) {
            return;
        }
        // DryRunWgAdapter would swallow the writes anyway, but then the log
        // would claim N peers were re-applied when nothing reached the kernel.
        // Say what actually happened instead.
        if (settingsSvc.get().firewallDryRun) {
            LOG.info("wg boot repush skipped — firewall writes are paused (dry-run)");
            return;
        }
        try {
            int count = peers.repushEnabledPeers();
            LOG.infof("wg boot repush: %d enabled peer(s) re-applied to '%s'", count, wgInterface);
        } catch (Exception ex) {
            LOG.errorf(ex, "wg boot repush failed — peers may be missing from '%s' until the next change",
                    wgInterface);
        }
    }
}
