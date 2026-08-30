package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User-level access expiry (issue #53). The gap this closes: the Peer-Scheduler
 * time-boxes a device, not a person, so a contractor whose peer had just
 * expired could open the self-service portal and enrol a fresh, unlimited one.
 */
@QuarkusTest
class UserAccessExpiryTest {

    @Inject UserAccessExpiryJob job;

    record Fixture(String userId, String peerId) {}

    private Fixture seed(Instant validUntil) {
        // Fresh per call, not per test class — one test seeds two users, and
        // users.email is unique.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return QuarkusTransaction.requiringNew().call(() -> {
            User u = User.createNew("EXP-U-" + suffix, "expu-" + suffix + "@example.test");
            u.validUntil = validUntil;
            u.persist();
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            Peer p = Peer.createNew(u.id, "exp-peer-" + suffix,
                    Base64.getEncoder().encodeToString(key), nextIp());
            p.persist();
            return new Fixture(u.id, p.id);
        });
    }

    /** The suite shares one in-memory DB, so a hardcoded IP collides across classes. */
    private static synchronized String nextIp() {
        return "10.8.7." + (2 + (ipSeq++ % 200));
    }
    private static int ipSeq = 0;

    @Test
    void accessAllowedAt_isEnabledAndInsideTheWindow() {
        User u = new User();
        u.enabled = true;
        assertThat(u.accessAllowedAt(Instant.now())).as("no deadline = no expiry").isTrue();

        u.validUntil = Instant.now().plusSeconds(3600);
        assertThat(u.accessAllowedAt(Instant.now())).isTrue();

        u.validUntil = Instant.now().minusSeconds(60);
        assertThat(u.accessAllowedAt(Instant.now())).as("past the deadline").isFalse();

        u.validUntil = null;
        u.enabled = false;
        assertThat(u.accessAllowedAt(Instant.now()))
                .as("a disabled user is out regardless of any deadline").isFalse();
    }

    /**
     * The cascade: blocking the login alone leaves a peer configured while the
     * window was open working indefinitely, since Peer.enabled is independent
     * of the person.
     */
    @Test
    void expireDue_disablesTheUsersPeers() {
        Fixture f = seed(Instant.now().minusSeconds(60));

        QuarkusTransaction.requiringNew().run(job::expireDue);

        Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(f.peerId()));
        assertThat(reloaded.enabled).isFalse();
    }

    /**
     * Marked "manual", not "schedule": PeerScheduleJob leaves manual-sourced
     * peers alone, and any other source would let a recurring weekly window
     * switch an expired contractor's device straight back on.
     */
    @Test
    void expireDue_marksPeersManualSoTheSchedulerCannotReviveThem() {
        Fixture f = seed(Instant.now().minusSeconds(60));

        QuarkusTransaction.requiringNew().run(job::expireDue);

        Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(f.peerId()));
        assertThat(reloaded.enabledSource).isEqualTo("manual");
    }

    @Test
    void expireDue_leavesAUserInsideTheirWindowAlone() {
        Fixture f = seed(Instant.now().plusSeconds(3600));

        QuarkusTransaction.requiringNew().run(job::expireDue);

        Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(f.peerId()));
        assertThat(reloaded.enabled).isTrue();
    }

    @Test
    void expireDue_leavesAUserWithoutADeadlineAlone() {
        Fixture f = seed(null);

        QuarkusTransaction.requiringNew().run(job::expireDue);

        Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(f.peerId()));
        assertThat(reloaded.enabled)
                .as("no deadline is the default — it must never withdraw access")
                .isTrue();
    }

    @Test
    void expireDue_audits() {
        seed(Instant.now().minusSeconds(60));

        QuarkusTransaction.requiringNew().run(job::expireDue);

        long entries = QuarkusTransaction.requiringNew().call(() ->
                AuditLog.count("action = ?1 and actor = ?2",
                        "user.access_expire", "system:user-access-expiry"));
        assertThat(entries)
                .as("the timeline must not imply an admin revoked it")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * A second tick over an already-expired user must stay silent, or every
     * minute would re-audit the same expiry forever.
     */
    @Test
    void expireDue_isIdempotent_doesNotReAuditOnEveryTick() {
        seed(Instant.now().minusSeconds(60));

        QuarkusTransaction.requiringNew().run(job::expireDue);
        long after1 = QuarkusTransaction.requiringNew().call(() ->
                AuditLog.count("action", "user.access_expire"));
        QuarkusTransaction.requiringNew().run(job::expireDue);
        long after2 = QuarkusTransaction.requiringNew().call(() ->
                AuditLog.count("action", "user.access_expire"));

        assertThat(after2).isEqualTo(after1);
    }

    /**
     * Extending the deadline does not resurrect the peers the job switched
     * off. Deciding a device may reconnect stays an explicit admin action —
     * the same rule the manual user-disable cascade follows.
     */
    @Test
    void extendingTheDeadlineDoesNotSwitchPeersBackOn() {
        Fixture f = seed(Instant.now().minusSeconds(60));
        QuarkusTransaction.requiringNew().run(job::expireDue);

        QuarkusTransaction.requiringNew().run(() -> {
            User u = User.findById(f.userId());
            u.validUntil = Instant.now().plusSeconds(86400);
        });

        Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(f.peerId()));
        assertThat(reloaded.enabled).isFalse();
        // ...but the person is allowed again, so they can enrol a new device.
        User u = QuarkusTransaction.requiringNew().call(() -> User.findById(f.userId()));
        assertThat(u.accessAllowedAt(Instant.now())).isTrue();
    }

    @Test
    void expireDue_handlesSeveralUsersInOneTick() {
        List<Fixture> all = List.of(
                seed(Instant.now().minusSeconds(60)),
                seed(Instant.now().minusSeconds(120)));

        QuarkusTransaction.requiringNew().run(job::expireDue);

        for (Fixture f : all) {
            Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(f.peerId()));
            assertThat(reloaded.enabled).isFalse();
        }
    }
}
