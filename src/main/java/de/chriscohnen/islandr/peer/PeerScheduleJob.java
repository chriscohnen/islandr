package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.firewall.RulesetService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Minute-tick that flips {@link Peer#enabled} based on {@link PeerSchedule}
 * and {@link Peer#validUntil} (issue #47), mirroring the audit+recompute
 * sequence {@code PeerResource}'s admin-initiated {@code /enabled} endpoint
 * already uses. Three passes per tick, in order:
 *
 * <ol>
 *   <li>Expired {@code validUntil} peers — disabled unconditionally and
 *       terminally, regardless of any recurring schedule or manual state.</li>
 *   <li>Schedule-owned peers ({@code enabledSource != "manual"}) — forced to
 *       match {@link PeerScheduleService#evaluateWindow}.</li>
 *   <li>Manually-owned peers whose window just crossed an edge (open&lt;-&gt;close)
 *       since the previous tick — the edge is treated as authoritative, the
 *       peer is flipped to match, and {@code enabledSource} resets to
 *       {@code "schedule"}. A tick the job never ran (e.g. downtime) can miss
 *       an edge this way; accepted as a known v1 limitation rather than
 *       persisting extra state to make this fully robust.</li>
 * </ol>
 *
 * One {@code rulesets.recomputeFromHook()} call per tick, not per peer.
 */
@ApplicationScoped
public class PeerScheduleJob {

    private static final Logger LOG = Logger.getLogger(PeerScheduleJob.class);
    private static final String SYSTEM_ACTOR = "system:peer-schedule";
    private static final Duration TICK_INTERVAL = Duration.ofSeconds(60);

    @ConfigProperty(name = "islandr.peer-schedule.enabled", defaultValue = "true")
    boolean enabled;

    @Inject PeerService peers;
    @Inject PeerScheduleService schedules;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @Scheduled(every = "60s",
               identity = "islandr-peer-schedule",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void scheduledTick() {
        if (!enabled) return;
        tick();
    }

    /** Run one evaluation pass. Visible for tests so the core logic can be
     *  exercised without the config-flag gate (mirrors ProxyReconciler's
     *  scheduledReconcile/reconcileNow split). */
    @Transactional
    void tick() {
        Instant now = Instant.now();
        boolean anyChanged = false;

        anyChanged |= disableExpiredPeers(now);
        anyChanged |= applyScheduleOwnedPeers(now);
        anyChanged |= applyManualEdgeCrossings(now);

        if (anyChanged) rulesets.recomputeFromHook();
    }

    private boolean disableExpiredPeers(Instant now) {
        List<Peer> expired = Peer.<Peer>list("validUntil is not null and validUntil < ?1 and enabled = true", now);
        boolean changed = false;
        for (Peer p : expired) {
            flip(p, false, "validUntil-expired");
            changed = true;
        }
        return changed;
    }

    private boolean applyScheduleOwnedPeers(Instant now) {
        boolean changed = false;
        for (PeerSchedule s : PeerSchedule.<PeerSchedule>listAll()) {
            Peer p = Peer.findById(s.peerId);
            if (p == null || isExpired(p, now)) continue;
            if ("manual".equals(p.enabledSource)) continue;
            boolean shouldBeEnabled = schedules.evaluateWindow(s, now);
            if (p.enabled != shouldBeEnabled) {
                flip(p, shouldBeEnabled, "schedule");
                changed = true;
            }
        }
        return changed;
    }

    private boolean applyManualEdgeCrossings(Instant now) {
        boolean changed = false;
        Instant previousTick = now.minus(TICK_INTERVAL);
        for (PeerSchedule s : PeerSchedule.<PeerSchedule>listAll()) {
            Peer p = Peer.findById(s.peerId);
            if (p == null || isExpired(p, now)) continue;
            if (!"manual".equals(p.enabledSource)) continue;
            boolean nowActive = schedules.evaluateWindow(s, now);
            boolean wasActive = schedules.evaluateWindow(s, previousTick);
            if (nowActive != wasActive) {
                // An open<->close edge just happened — it wins over the
                // standing manual override, which is exactly the "manual
                // wins until the next transition" contract.
                flip(p, nowActive, "schedule");
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isExpired(Peer p, Instant now) {
        return p.validUntil != null && p.validUntil.isBefore(now);
    }

    private void flip(Peer p, boolean newState, String reason) {
        peers.setEnabledBySchedule(p.id, newState);
        audit.logEvent(SYSTEM_ACTOR, newState ? "peer.enable" : "peer.disable",
                "Peer:" + p.name + " (" + p.id + ")", Map.of("reason", reason));
        LOG.infof("peer-schedule: %s peer %s (%s) — reason=%s", newState ? "enabled" : "disabled",
                p.name, p.id, reason);
    }
}
