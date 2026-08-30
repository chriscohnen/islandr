package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled half of issue #72's expiry: a reservation whose window has
 * closed must end up EXPIRED (not CANCELLED — nobody let go, time ran out)
 * and must leave an audit entry attributing it to the system, not a person.
 */
@QuarkusTest
class ReservationExpiryJobTest {

    @Inject ReservationExpiryJob job;
    @Inject ReservationService reservations;

    private String seedLiveReservation(boolean alreadyElapsed) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("EXP-Site-" + suffix, "10.95.0.0/24", null);
            site.persist();
            Resource res = Resource.createNew(site.id, "EXP-Res-" + suffix, "10.95.0.10", null, "computer");
            res.maxConcurrentUsers = 1;
            res.persist();
            User u = User.createNew("EXP User " + suffix, "exp-" + suffix + "@example.test");
            u.persist();

            ResourceReservation r = ResourceReservation.createPending(u.id, res.id, 60, Instant.now());
            r.activate(Instant.now());
            if (alreadyElapsed) {
                r.startsAt = Instant.now().minusSeconds(7200);
                r.endsAt = Instant.now().minusSeconds(60);
            }
            r.persist();
            return r.id;
        });
    }

    @Test
    void expireDue_closesAWindowThatHasElapsed() {
        String id = seedLiveReservation(true);

        QuarkusTransaction.requiringNew().run(job::expireDue);

        ResourceReservation reloaded =
                QuarkusTransaction.requiringNew().call(() -> ResourceReservation.findById(id));
        assertThat(reloaded.status)
                .as("time running out is EXPIRED, not CANCELLED — nobody released it")
                .isEqualTo(ResourceReservation.EXPIRED);
    }

    @Test
    void expireDue_leavesAStillRunningReservationAlone() {
        String id = seedLiveReservation(false);

        QuarkusTransaction.requiringNew().run(job::expireDue);

        ResourceReservation reloaded =
                QuarkusTransaction.requiringNew().call(() -> ResourceReservation.findById(id));
        assertThat(reloaded.status).isEqualTo(ResourceReservation.ACTIVE);
    }

    @Test
    void expireDue_auditsAgainstTheSystemActor_notAPerson() {
        seedLiveReservation(true);

        QuarkusTransaction.requiringNew().run(job::expireDue);

        long entries = QuarkusTransaction.requiringNew().call(() ->
                AuditLog.count("action = ?1 and actor = ?2",
                        "reservation.expire", "system:reservation-expiry"));
        assertThat(entries)
                .as("the timeline must not imply an admin revoked it")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void dueForExpiry_ignoresReservationsThatWereAlreadyClosed() {
        String id = seedLiveReservation(true);
        QuarkusTransaction.requiringNew().run(job::expireDue);

        // A second tick must not re-report the same row — otherwise every
        // tick would re-audit every historical reservation forever.
        boolean stillDue = QuarkusTransaction.requiringNew().call(() ->
                reservations.dueForExpiry(Instant.now()).stream().anyMatch(r -> r.id.equals(id)));
        assertThat(stillDue).isFalse();
    }
}
