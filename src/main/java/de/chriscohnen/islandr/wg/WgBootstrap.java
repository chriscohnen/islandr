package de.chriscohnen.islandr.wg;

import de.chriscohnen.islandr.proxy.ProxyMode;
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
 */
@ApplicationScoped
public class WgBootstrap {

    private static final Logger LOG = Logger.getLogger(WgBootstrap.class);

    @Inject WgAdapter wg;
    @Inject ProxyMode proxyMode;

    @ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    @ConfigProperty(name = "islandr.wg.boot-probe-enabled", defaultValue = "true")
    boolean enabled;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled || proxyMode.isSocket()) {
            return;
        }
        WgAdapter.ProbeResult probe = wg.probeServerDetailed(wgInterface);
        if (probe.reachable()) {
            LOG.infof("wg boot probe: interface '%s' reachable, %d peer(s)",
                    wgInterface, probe.info().peerCount());
        } else {
            LOG.warnf("wg boot probe: interface '%s' not reachable — %s. " +
                    "Check that WireGuard is installed and the interface is up (see docs/install.md).",
                    wgInterface, probe.error());
        }
    }
}
