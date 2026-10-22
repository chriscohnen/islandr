package de.chriscohnen.islandr.peer;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A 60-minute rolling sum of the bytes that actually moved, fed by the same
 * clamped per-tick deltas {@code ActivityPoller} already writes to
 * {@code peer_daily_activity}.
 *
 * <p>In-memory only, reset by a restart — the same "a live sample, not a
 * DB-backed stat" posture as {@link de.chriscohnen.islandr.dns.DnsQueryStats}
 * and {@code HostHealthSampler}. The alternative, hourly buckets in the
 * database, would be a migration and a retention job for a number nobody
 * reads twice. The dashboard says how long the window is, so a tile reading
 * low right after a restart explains itself.
 *
 * <p>One bucket per wall-clock minute rather than a true sliding window:
 * cheap, and nobody needs the tile accurate to the second.
 */
@ApplicationScoped
public class TrafficWindowStats {

    private static final long WINDOW_MINUTES = 60;

    private final ConcurrentHashMap<Long, AtomicLong> rx = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> tx = new ConcurrentHashMap<>();

    /** @param rxDelta bytes received since the previous sample, already clamped to >= 0
     *  @param txDelta bytes sent since the previous sample, already clamped to >= 0 */
    public void record(long rxDelta, long txDelta) {
        if (rxDelta <= 0 && txDelta <= 0) return;
        long minute = currentMinute();
        if (rxDelta > 0) add(rx, minute, rxDelta);
        if (txDelta > 0) add(tx, minute, txDelta);
    }

    private void add(ConcurrentHashMap<Long, AtomicLong> buckets, long minute, long delta) {
        buckets.computeIfAbsent(minute, k -> new AtomicLong()).addAndGet(delta);
        // Opportunistic prune on write — at most WINDOW_MINUTES+1 keys ever
        // accumulate, however often this is called.
        buckets.keySet().removeIf(m -> minute - m >= WINDOW_MINUTES);
    }

    public Snapshot snapshot() {
        long minute = currentMinute();
        return new Snapshot(sumWithin(rx, minute), sumWithin(tx, minute), WINDOW_MINUTES);
    }

    private long sumWithin(ConcurrentHashMap<Long, AtomicLong> buckets, long currentMinute) {
        long sum = 0;
        for (var entry : buckets.entrySet()) {
            if (currentMinute - entry.getKey() < WINDOW_MINUTES) sum += entry.getValue().get();
        }
        return sum;
    }

    private static long currentMinute() {
        return Instant.now().getEpochSecond() / 60;
    }

    public record Snapshot(long rxBytes, long txBytes, long windowMinutes) {}
}
