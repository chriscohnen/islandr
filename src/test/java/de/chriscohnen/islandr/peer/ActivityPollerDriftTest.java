package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.MockWgAdapter;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift reconcile: peers configured with {@code wg set} are kernel state only,
 * because Islandr never writes {@code /etc/wireguard/<iface>.conf}. A
 * {@code systemctl restart wg-quick@<iface>} under a running Islandr reloads
 * that file and drops every managed peer — the boot repush in {@code WgBootstrap}
 * cannot help, because Islandr itself never restarted. The poller already reads
 * the live key set every tick, so it is the cheapest place to notice and fix it.
 */
@QuarkusTest
@TestProfile(ActivityPollerDriftTest.DriftOn.class)
class ActivityPollerDriftTest {

    public static final class DriftOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("islandr.wg.drift-repush-enabled", "true");
        }
    }

    @Inject ActivityPoller poller;
    @Inject WgAdapter wg;

    @BeforeEach
    void setup() {
        wipe();
        mock().reset();
        dryRun(false);
    }

    @AfterEach
    void teardown() {
        wipe();
        mock().reset();
    }

    @Test
    void poll_reappliesAnEnabledPeerTheInterfaceLost() {
        String id = createPeer("10.79.0.5", true);
        String key = pubkeyOf(id);
        wg.setPeer("wg0", key, "10.79.0.5/32", null);

        mock().reset(); // wg-quick reloaded the file: kernel state gone, DB untouched
        assertThat(livePubkeys()).doesNotContain(key);

        poller.poll();

        assertThat(livePubkeys())
                .as("an enabled peer missing from the interface must be pushed back")
                .contains(key);
    }

    @Test
    void poll_leavesDisabledPeersOff() {
        String id = createPeer("10.79.0.6", false);
        String key = pubkeyOf(id);

        poller.poll();

        assertThat(livePubkeys())
                .as("a disabled peer must not be revived by the drift reconcile")
                .doesNotContain(key);
    }

    @Test
    void poll_doesNotWriteWhileFirewallWritesArePaused() {
        String id = createPeer("10.79.0.7", true);
        String key = pubkeyOf(id);
        dryRun(true);

        poller.poll();

        assertThat(livePubkeys())
                .as("dry-run must suppress the drift repush")
                .doesNotContain(key);
    }

    // -- helpers -------------------------------------------------------------

    private MockWgAdapter mock() {
        return (MockWgAdapter) io.quarkus.arc.ClientProxy.unwrap(wg);
    }

    private java.util.List<String> livePubkeys() {
        return mock().showPeers("wg0").stream().map(WgAdapter.PeerStatus::publicKey).toList();
    }

    @Transactional
    void dryRun(boolean on) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.firewallDryRun = on;
    }

    @Transactional
    void wipe() {
        PeerDailyActivity.deleteAll();
        Peer.deleteAll();
        User.deleteAll();
    }

    @Transactional
    String createPeer(String ip, boolean enabled) {
        User u = User.createNew("Owner " + UUID.randomUUID(), "owner-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = u.id;
        p.name = "drift-" + ip;
        p.publicKey = "pk-" + UUID.randomUUID();
        p.assignedIp = ip;
        p.enabled = enabled;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.type = "client";
        p.persist();
        return p.id;
    }

    @Transactional
    String pubkeyOf(String id) {
        Peer p = Peer.findById(id);
        return p.publicKey;
    }
}
