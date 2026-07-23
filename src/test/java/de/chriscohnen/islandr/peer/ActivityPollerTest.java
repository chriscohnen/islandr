package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.MockWgAdapter;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The poller is driven directly (not via the scheduler) so the assertion is
 * deterministic. The %test profile already disables the @Scheduled tick, so
 * we can call {@link ActivityPoller#poll()} synchronously without racing.
 *
 * <p>Setup and assertions each run in their own transaction (not a single
 * test-wide @Transactional). That matches production: poll() commits before
 * the dashboard query reads. Within one transaction the persistence context
 * would mask the write/read sequence.
 */
@QuarkusTest
class ActivityPollerTest {

    @Inject ActivityPoller poller;
    @Inject WgAdapter wg;

    @BeforeEach
    void cleanup() {
        wipe();
        mock().reset();
    }

    @AfterEach
    void teardown() {
        wipe();
        mock().reset();
    }

    @Transactional
    void wipe() {
        PeerDailyActivity.deleteAll();
        Peer.deleteAll();
        User.deleteAll();
    }

    @Test
    void poll_updatesLastSeenForKnownPeer() {
        String peerId = createPeerAndRegisterWithWg("10.8.0.5");

        // The mock returns lastHandshake!=null on roughly 70% of calls. Loop
        // until at least one poll() observes a non-null handshake and writes.
        // P(20× null) ≈ 3.5e-11 — effectively zero.
        boolean updated = false;
        for (int i = 0; i < 20 && !updated; i++) {
            poller.poll();
            updated = lastSeenAtOf(peerId) != null;
        }
        assertThat(updated)
                .as("poller should have written lastSeenAt after at most 20 ticks")
                .isTrue();
    }

    @Test
    void poll_ignoresPeerNotOnInterface() {
        String peerId = createPeer("10.8.0.7");
        // Deliberately do NOT call wg.setPeer — wg has no record.
        poller.poll();
        assertThat(lastSeenAtOf(peerId)).isNull();
    }

    @Test
    void poll_bumpsDailyActivityOnHandshake() {
        String peerId = createPeerAndRegisterWithWg("10.8.0.9");

        int hits = 0;
        for (int i = 0; i < 20; i++) {
            poller.poll();
            hits = dailySampleHits(peerId);
            if (hits > 0) break;
        }
        assertThat(hits).as("sample_hits should increment after a poll observes a handshake").isGreaterThan(0);

        int before = hits;
        for (int i = 0; i < 20 && dailySampleHits(peerId) == before; i++) poller.poll();
        assertThat(dailySampleHits(peerId))
                .as("a later poll on the same UTC day should keep incrementing the same row, not create a new one")
                .isGreaterThan(before);
        assertThat(countActivityRows(peerId)).isEqualTo(1);
    }

    @Test
    void poll_doesNotResetExistingLastSeenWhenPeerVanishesFromWg() {
        // A peer that was seen yesterday and is no longer on the interface
        // (e.g. wg flushed after a reboot before the next handshake) must not
        // have its lastSeenAt cleared. The poller only writes, never erases.
        String peerId = createPeer("10.8.0.8");
        // SQLite truncates Instant to milliseconds — round before storing so
        // the assertion below compares apples to apples.
        Instant marker = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
        setLastSeenAt(peerId, marker);

        poller.poll();
        Instant after = lastSeenAtOf(peerId);
        assertThat(after).isEqualTo(marker);
    }

    // -- helpers (each its own short transaction) -----------------------------

    @Transactional
    String createPeer(String ip) {
        User u = User.createNew("Owner " + UUID.randomUUID(), "owner-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = u.id;
        p.name = "peer-" + UUID.randomUUID().toString().substring(0, 6);
        p.publicKey = "pk-" + UUID.randomUUID();
        p.assignedIp = ip;
        p.enabled = true;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.type = "client";
        p.persist();
        return p.id;
    }

    String createPeerAndRegisterWithWg(String ip) {
        String id = createPeer(ip);
        Peer p = findPeer(id);
        wg.setPeer("wg0", p.publicKey, p.assignedIp + "/32", null);
        return id;
    }

    @Transactional
    Peer findPeer(String id) {
        return Peer.findById(id);
    }

    @Transactional
    Instant lastSeenAtOf(String id) {
        Peer p = Peer.findById(id);
        return p == null ? null : p.lastSeenAt;
    }

    @Transactional
    void setLastSeenAt(String id, Instant value) {
        Peer p = Peer.findById(id);
        p.lastSeenAt = value;
    }

    @Transactional
    int dailySampleHits(String peerId) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        PeerDailyActivity row = PeerDailyActivity.findById(new PeerDailyActivity.Id(peerId, today));
        return row == null ? 0 : row.sampleHits;
    }

    @Transactional
    long countActivityRows(String peerId) {
        return PeerDailyActivity.count("id.peerId = ?1", peerId);
    }

    private MockWgAdapter mock() {
        return (MockWgAdapter) io.quarkus.arc.ClientProxy.unwrap(wg);
    }
}
