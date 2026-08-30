package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Closes reservations whose window has run out (issue #72) — the same
 * "system-actor scheduled job flips enforcement state, then audits and
 * recomputes" shape as {@link UserGrantExpiryJob} and
 * {@link de.chriscohnen.islandr.peer.PeerScheduleJob}.
 *
 * <p>The recompute is what actually withdraws access: an expired reservation
 * stops satisfying {@link RuleBuilder}'s gate, so the resource's rules for
 * that user disappear on the next ruleset build.
 *
 * <p>{@code reservation.expire} is deliberately a different audit action from
 * the {@code reservation.cancel} a self-release produces — the timeline should
 * read honestly that time ran out rather than that someone let go.
 */
@ApplicationScoped
public class ReservationExpiryJob {

    private static final Logger LOG = Logger.getLogger(ReservationExpiryJob.class);
    private static final String SYSTEM_ACTOR = "system:reservation-expiry";

    @Inject ReservationService reservations;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @Scheduled(every = "60s",
               identity = "islandr-reservation-expiry",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            expireDue();
        } catch (Exception ex) {
            // Same posture as the other jobs: a failed tick must never kill
            // the scheduler — the next one gets another shot a minute later.
            LOG.warnf("reservation expiry tick failed: %s", ex.getMessage());
        }
    }

    @Transactional
    void expireDue() {
        List<ResourceReservation> due = reservations.dueForExpiry(Instant.now());
        if (due.isEmpty()) return;

        for (ResourceReservation r : due) {
            r.status = ResourceReservation.EXPIRED;
            User user = User.findById(r.userId);
            Resource res = Resource.findById(r.resourceId);
            String userName = user == null ? r.userId : user.name;
            String resourceName = res == null ? r.resourceId : res.name;
            audit.logEvent(SYSTEM_ACTOR, "reservation.expire",
                    "Reservation:" + userName + "/" + resourceName,
                    Map.of("reason", "reservation window elapsed"));
        }
        rulesets.recomputeFromHook();
    }
}
