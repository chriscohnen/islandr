package de.chriscohnen.islandr.proxy;

import de.chriscohnen.islandr.firewall.FirewallState;
import de.chriscohnen.islandr.firewall.MockNftablesAdapter;
import de.chriscohnen.islandr.firewall.NftablesAdapter;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.MockWgAdapter;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProxyReconciler} reconcile-on-connect (design §6). Drives
 * {@code reconcileNow()} directly (bypassing the socket-mode schedule gate) with
 * the mock adapters, whose {@code forceUnavailable} seam simulates the proxy
 * being reachable or not.
 */
@QuarkusTest
class ProxyReconcilerTest {

    @Inject ProxyReconciler reconciler;
    @Inject WgAdapter wgAdapter;
    @Inject NftablesAdapter nftAdapter;
    @Inject EnforcementStatus enforcement;

    private MockWgAdapter wgMock() {
        return (MockWgAdapter) ClientProxy.unwrap(wgAdapter);
    }

    private MockNftablesAdapter nftMock() {
        return (MockNftablesAdapter) ClientProxy.unwrap(nftAdapter);
    }

    @BeforeEach
    @Transactional
    void reset() {
        Peer.deleteAll();
        FirewallState s = FirewallState.get();
        s.lastStatus = FirewallState.NEVER;
        s.rulesetText = null;
        s.stderrText = null;
        wgMock().reset();
        nftMock().resetForTests();
        enforcement.markActive();
    }

    @Transactional
    void seedEnabledPeer(String publicKey, String ip) {
        User u = User.createNew("Reconcile User", "reconcile-" + java.util.UUID.randomUUID() + "@firma.de");
        u.persist();
        Peer p = Peer.createNew(u.id, "reconcile-peer", publicKey, ip);
        p.enabled = true;
        p.persist();
    }

    /** BR-030 / §6: proxy back + status was UNAVAILABLE → RECONCILING → full re-apply → ACTIVE, peers re-pushed. */
    @Test
    void reconcile_whenUnavailableAndProbeOk_marksActiveAndRepushesEnabledPeers() {
        seedEnabledPeer("RECONCILEPUBKEYAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "10.8.0.40");
        enforcement.markUnavailable("was down");

        reconciler.reconcileNow();

        assertThat(enforcement.state()).isEqualTo(EnforcementStatus.State.ACTIVE);
        assertThat(wgMock().showPeers("wg0"))
                .anyMatch(p -> p.publicKey().equals("RECONCILEPUBKEYAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }

    /** §6 step 3: probe fails → UNAVAILABLE. */
    @Test
    void reconcile_whenProbeFails_marksUnavailable() {
        enforcement.markActive();
        wgMock().forceUnavailable = true;

        reconciler.reconcileNow();

        assertThat(enforcement.state()).isEqualTo(EnforcementStatus.State.UNAVAILABLE);
    }

    /** #37: the real probe failure reason must reach EnforcementStatus, not a generic string. */
    @Test
    void reconcile_whenProbeFails_recordsTheRealReason() {
        enforcement.markActive();
        wgMock().forceUnavailable = true;

        reconciler.reconcileNow();

        assertThat(enforcement.lastError()).isEqualTo("mock: forced unavailable");
    }

    /** Already ACTIVE + probe ok → no re-apply needed, stays ACTIVE. */
    @Test
    void reconcile_whenActiveAndProbeOk_staysActive() {
        enforcement.markActive();

        reconciler.reconcileNow();

        assertThat(enforcement.state()).isEqualTo(EnforcementStatus.State.ACTIVE);
    }
}
