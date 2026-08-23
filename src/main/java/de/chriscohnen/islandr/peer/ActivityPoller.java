package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.wg.WgAdapter;
import de.chriscohnen.islandr.webhook.WebhookDispatcher;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodically samples {@code wg show <iface> dump} and writes the per-peer
 * activity columns back to the DB. The dashboard topology uses
 * {@code peer.lastSeenAt} to decide whether a peer dot is rendered as
 * "live" (within the 5-minute window) or "idle".
 *
 * <p>Byte counters from {@code wg} reset to 0 on interface restart, so raw
 * values are never stored directly — only the clamped-to-non-negative delta
 * per tick, both onto {@link Peer#totalRxBytes}/{@code totalTxBytes} (all-time)
 * and onto the current day's {@link PeerDailyActivity} row (for the heatmap's
 * traffic-volume coloring mode).
 *
 * <p>Disabled by setting {@code islandr.activity.poll-enabled=false}; the
 * test profile turns it off so unit tests don't see the dashboard mutate
 * underneath them.
 */
@ApplicationScoped
public class ActivityPoller {

    private static final Logger LOG = Logger.getLogger(ActivityPoller.class);

    @ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    @ConfigProperty(name = "islandr.activity.poll-enabled", defaultValue = "true")
    boolean pollEnabled;

    @Inject WgAdapter wg;
    @Inject WebhookDispatcher webhooks;

    /**
     * Runs every 30s. 30s + 5-minute live window means a real peer with a
     * 25s WireGuard keepalive will reliably show as live in the dashboard.
     *
     * <p>The disable flag is checked inline rather than via
     * {@link Scheduled#skipExecutionIf} because the latter requires Quarkus to
     * instantiate a separate {@link io.quarkus.scheduler.Scheduled.SkipPredicate}
     * bean, which trips up native-image when that bean is a nested class.
     */
    @Scheduled(every = "30s",
               delayed = "10s",
               identity = "islandr-activity-poller",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sample() {
        if (!pollEnabled) return;
        try {
            poll();
        } catch (Exception ex) {
            // Never let a poll failure crash the scheduler thread — the next
            // tick should get another shot. Log at WARN so it surfaces in
            // journalctl without spamming.
            LOG.warnf("activity poll failed: %s", ex.getMessage());
        }
    }

    @Transactional
    void poll() {
        List<WgAdapter.PeerStatus> statuses;
        try {
            statuses = wg.showPeers(wgInterface);
        } catch (Exception ex) {
            LOG.debugf("wg showPeers threw: %s", ex.getMessage());
            detectConnectionTransitions(Instant.now());
            return;
        }
        if (statuses.isEmpty()) {
            detectConnectionTransitions(Instant.now());
            return;
        }

        Map<String, WgAdapter.PeerStatus> byPubkey = new HashMap<>();
        for (WgAdapter.PeerStatus s : statuses) {
            byPubkey.put(s.publicKey(), s);
        }

        // Single query: pull only peers whose pubkey wg knows. Avoids touching
        // peers that aren't on the interface (yet) at all.
        List<Peer> peers = Peer.<Peer>list("publicKey in ?1", byPubkey.keySet());
        Instant now = Instant.now();
        int updated = 0;
        for (Peer p : peers) {
            WgAdapter.PeerStatus s = byPubkey.get(p.publicKey);
            if (s == null) continue;
            // Only count a peer as "seen" if wg reports a non-null handshake.
            // A peer that's configured but never connected has handshake=null
            // and must stay null in the DB.
            if (s.lastHandshake() == null) continue;

            p.lastSeenAt = now;
            p.lastSeenEndpoint = s.endpoint();

            // Delta-accumulation: wg counters reset to 0 on interface restart.
            // A positive delta means bytes flowed; a negative delta means the
            // counter was reset — treat as 0 so totals are monotonically increasing.
            long rxDelta = s.rxBytes() - p.lastSampledRxBytes;
            long txDelta = s.txBytes() - p.lastSampledTxBytes;
            if (rxDelta > 0) p.totalRxBytes += rxDelta;
            if (txDelta > 0) p.totalTxBytes += txDelta;
            p.lastSampledRxBytes = s.rxBytes();
            p.lastSampledTxBytes = s.txBytes();

            bumpDailyActivity(p.id, now, Math.max(0, rxDelta), Math.max(0, txDelta));

            updated++;
        }
        if (updated > 0) LOG.debugf("activity poll: updated %d peer(s)", updated);
        detectConnectionTransitions(now);
    }

    // Keyed by peer id, in-memory only — resets on restart, so the very
    // first tick after boot never fires a false transition (no baseline to
    // compare against yet, see the null-check below). Runs over EVERY peer
    // each tick, not just the ones `wg` reported a handshake for this time
    // around: a peer that goes fully silent (no wg report at all, e.g. the
    // WireGuard interface itself has an issue) still needs to age from
    // CONNECTED into STALE/DISCONNECTED purely by time passing, and this is
    // the only place that notices that transition (issue #68).
    private final Map<String, PeerConnectionStatus> lastKnownStatus = new java.util.concurrent.ConcurrentHashMap<>();

    private void detectConnectionTransitions(Instant now) {
        for (Peer p : Peer.<Peer>listAll()) {
            PeerConnectionStatus current = PeerConnectionStatus.of(p.lastSeenAt, now);
            PeerConnectionStatus previous = lastKnownStatus.put(p.id, current);
            if (previous == null || previous == current) continue;
            boolean wasConnected = previous == PeerConnectionStatus.CONNECTED;
            boolean isConnected = current == PeerConnectionStatus.CONNECTED;
            if (isConnected && !wasConnected) {
                webhooks.publish(de.chriscohnen.islandr.webhook.WebhookEventType.PEER_CONNECTED,
                        "system:activity-poller", "Peer:" + p.id, Map.of("peerId", p.id, "name", p.name));
            } else if (!isConnected && wasConnected) {
                webhooks.publish(de.chriscohnen.islandr.webhook.WebhookEventType.PEER_DISCONNECTED,
                        "system:activity-poller", "Peer:" + p.id, Map.of("peerId", p.id, "name", p.name));
            }
        }
    }

    /**
     * Upserts the peer_daily_activity row for the peer's UTC day, incrementing
     * sample_hits and adding this tick's already-clamped rx/tx deltas. Backs
     * the dashboard's connection activity heatmap (#32) and its traffic-volume
     * coloring mode.
     */
    private void bumpDailyActivity(String peerId, Instant sampledAt, long rxDelta, long txDelta) {
        LocalDate day = sampledAt.atZone(ZoneOffset.UTC).toLocalDate();
        PeerDailyActivity.Id id = new PeerDailyActivity.Id(peerId, day);
        PeerDailyActivity row = PeerDailyActivity.findById(id);
        if (row == null) {
            row = new PeerDailyActivity(peerId, day);
            row.sampleHits = 1;
            row.rxBytes = rxDelta;
            row.txBytes = txDelta;
            row.persist();
        } else {
            row.sampleHits++;
            row.rxBytes += rxDelta;
            row.txBytes += txDelta;
        }
    }
}
