package de.chriscohnen.islandr.proxy;

import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.peer.PeerService;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reconcile-on-connect for the socket-proxy enforcement plane (design §6).
 *
 * <p>On a fixed cadence (and at startup) it probes the proxy. When the proxy is
 * reachable again after having been down, it does a <em>full re-apply</em> — the
 * nftables ruleset plus every enabled peer — so the live host state converges to
 * the DB, which is the source of truth. A full re-apply (not a delta) is safe
 * because the ruleset is a whole-table replacement (BR-025) and {@code wg set} is
 * idempotent.
 *
 * <p>Only active in socket mode ({@link ProxyMode#isSocket()}); in {@code real}/
 * {@code mock} mode the schedule is a no-op so enforcement status is never
 * spuriously flipped. {@link #reconcileNow()} runs one cycle regardless of the
 * gate and is the unit under test.
 */
@ApplicationScoped
public class ProxyReconciler {

    private static final Logger LOG = Logger.getLogger(ProxyReconciler.class);

    @Inject WgAdapter wg;
    @Inject RulesetService rulesets;
    @Inject PeerService peers;
    @Inject EnforcementStatus enforcement;
    @Inject ProxyMode proxyMode;

    @ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    /** At boot, reflect the true enforcement state instead of the ACTIVE default. */
    void onStart(@Observes StartupEvent ev) {
        scheduledReconcile();
    }

    @Scheduled(every = "{islandr.proxy.reconcile-interval}")
    void scheduledReconcile() {
        if (!proxyMode.isSocket()) {
            return; // real/mock mode enforce directly — nothing to reconcile
        }
        reconcileNow();
    }

    /**
     * Run one probe + (if needed) reconcile cycle. Visible for tests so the core
     * logic can be exercised without the socket-mode schedule gate.
     */
    void reconcileNow() {
        boolean reachable = wg.probeServer(wgInterface) != null;
        if (!reachable) {
            enforcement.markUnavailable("proxy probe failed");
            return;
        }
        if (enforcement.state() != EnforcementStatus.State.UNAVAILABLE) {
            return; // already reconciled — nothing to do
        }

        LOG.info("proxy reachable again — reconciling enforcement plane (full re-apply)");
        enforcement.markReconciling();
        try {
            // nftables: whole-table replacement. Marks ACTIVE internally on success;
            // a real nft rejection sets FirewallState=FAILED but keeps proxy=reachable.
            rulesets.recomputeAndApply("system:reconcile");
            // wg: re-push every enabled peer so the live interface matches the DB.
            peers.repushEnabledPeers();
            enforcement.markActive();
        } catch (ProxyUnavailableException e) {
            // Proxy dropped mid-reconcile — back to degraded, next cycle retries.
            LOG.warnf("proxy dropped during reconcile: %s", e.getMessage());
            enforcement.markUnavailable(e.getMessage());
        }
    }
}
