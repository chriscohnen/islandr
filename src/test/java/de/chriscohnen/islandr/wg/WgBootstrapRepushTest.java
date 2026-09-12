package de.chriscohnen.islandr.wg;

import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Peers configured with {@code wg set} live in kernel state only — Islandr never
 * writes {@code /etc/wireguard/<iface>.conf}. A host reboot or a
 * {@code systemctl restart wg-quick@wg0} therefore brings the interface back
 * carrying only the peers in that file, and every Islandr-managed peer is gone
 * (observed on a live hub: 12 peers before the restart, 3 after). Nothing else
 * converges the interface back to the DB in real mode, so {@link WgBootstrap}
 * re-pushes at startup.
 */
@QuarkusTest
@TestProfile(WgBootstrapRepushTest.RepushOn.class)
class WgBootstrapRepushTest {

    public static final class RepushOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("islandr.wg.boot-repush-enabled", "true");
        }
    }

    @Inject WgBootstrap bootstrap;
    @Inject WgAdapter wg;

    private MockWgAdapter mock() {
        return (MockWgAdapter) io.quarkus.arc.ClientProxy.unwrap(wg);
    }

    private void dryRun(boolean on) {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.firewallDryRun = on;
        });
    }

    private String givenEnabledPeer(String publicKey, String ip) {
        String[] id = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Peer p = Peer.createNew(null, "repush-" + ip, publicKey, ip);
            p.type = "client";
            p.enabled = true;
            p.persist();
            id[0] = p.id;
        });
        return id[0];
    }

    /** The interface came back empty (wg-quick reloaded the file) — boot must refill it. */
    @Test
    void bootRepush_reappliesEnabledPeersAfterTheInterfaceWasReloaded() {
        String key = "RePuSh0000000000000000000000000000000000000=";
        givenEnabledPeer(key, "10.77.0.11");
        dryRun(false);

        mock().reset(); // the reboot: kernel state gone, DB untouched
        assertTrue(mock().showPeers("wg0").stream().noneMatch(p -> key.equals(p.publicKey())),
                "precondition: the peer is not on the interface");

        bootstrap.repushPeers();

        assertTrue(mock().showPeers("wg0").stream().anyMatch(p -> key.equals(p.publicKey())),
                "enabled peer must be back on the interface after the boot repush");
    }

    /** Dry-run means the admin has not activated enforcement — boot must not write either. */
    @Test
    void bootRepush_doesNothingWhileFirewallWritesArePaused() {
        String key = "DrYrUn00000000000000000000000000000000000000=";
        givenEnabledPeer(key, "10.77.0.12");
        dryRun(true);

        mock().reset();
        bootstrap.repushPeers();

        assertEquals(0, mock().showPeers("wg0").size(),
                "dry-run must suppress the boot repush, not just the individual writes");
    }

    /** A disabled peer stays off the interface — the repush is not a blanket re-enable. */
    @Test
    void bootRepush_skipsDisabledPeers() {
        String key = "DiSaBlEd0000000000000000000000000000000000A=";
        String id = givenEnabledPeer(key, "10.77.0.13");
        QuarkusTransaction.requiringNew().run(() -> {
            Peer p = Peer.findById(id);
            p.enabled = false;
        });
        dryRun(false);

        mock().reset();
        bootstrap.repushPeers();

        assertTrue(mock().showPeers("wg0").stream().noneMatch(p -> key.equals(p.publicKey())),
                "a disabled peer must not be re-pushed");
    }
}
