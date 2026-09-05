package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Withdraws network access from users whose access window has closed
 * (issue #53) — the same "system-actor scheduled job flips enforcement state,
 * then audits and recomputes" shape as
 * {@link de.chriscohnen.islandr.peer.PeerScheduleJob} and
 * {@link de.chriscohnen.islandr.acl.UserGrantExpiryJob}.
 *
 * <p>Blocking the login is not enough on its own: a peer configured while the
 * window was open keeps working indefinitely, since {@code Peer.enabled} is
 * independent of the person. So the job cascades to every one of the user's
 * peers, the same {@code wg.removePeer()} path a manual per-peer disable
 * takes.
 *
 * <p>Peers are marked {@code enabledSource="manual"} on the way down,
 * deliberately: {@link de.chriscohnen.islandr.peer.PeerScheduleJob} leaves
 * manual-sourced peers alone, and any other source would let a recurring
 * weekly window switch an expired contractor's device straight back on.
 *
 * <p>Expiry does <em>not</em> flip {@code User.enabled}. That field records an
 * admin's standing decision and this one records a deadline; conflating them
 * would mean extending a deadline silently un-does a deliberate disable.
 * Re-activating is an explicit admin action either way — extending
 * {@code validUntil} restores the ability to enrol a new device, but the peers
 * this job switched off stay off until someone turns them back on, exactly as
 * the manual user-disable cascade behaves.
 */
@ApplicationScoped
public class UserAccessExpiryJob {

    private static final Logger LOG = Logger.getLogger(UserAccessExpiryJob.class);
    private static final String SYSTEM_ACTOR = "system:user-access-expiry";

    // Off in the test profile, same as every other scheduled job here: a
    // background tick competing with a test's own transaction deadlocks
    // SQLite's shared cache, and the tests drive the job directly anyway.
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "islandr.user-access-expiry.enabled", defaultValue = "true")
    boolean enabled;

    @Inject PeerService peers;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @Scheduled(every = "60s",
               identity = "islandr-user-access-expiry",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        if (!enabled) return;
        try {
            expireDue();
        } catch (Exception ex) {
            // Same posture as the other jobs: a failed tick must never kill
            // the scheduler — the next one gets another shot a minute later.
            LOG.warnf("user-access expiry tick failed: %s", ex.getMessage());
        }
    }

    @Transactional
    void expireDue() {
        Instant now = Instant.now();
        List<User> due = User.list("validUntil is not null and validUntil <= ?1", now);
        if (due.isEmpty()) return;

        boolean anyChange = false;
        for (User u : due) {
            // Only the peers still up matter. Re-running over a user whose
            // peers are already down must stay silent, or every tick would
            // re-audit the same expiry forever.
            List<Peer> live = Peer.list("userId = ?1 and enabled = true", u.id);
            if (live.isEmpty()) continue;

            for (Peer p : live) peers.setEnabled(p.id, false);
            anyChange = true;
            audit.logEvent(SYSTEM_ACTOR, "user.access_expire",
                    "User:" + u.name + " (" + u.id + ")",
                    Map.of("reason", "validUntil elapsed",
                           "peersDisabled", String.valueOf(live.size())));
        }
        if (anyChange) rulesets.recomputeFromHook();
    }
}
