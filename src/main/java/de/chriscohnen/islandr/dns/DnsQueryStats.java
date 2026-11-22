package de.chriscohnen.islandr.dns;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A 30-minute rolling count of DNS queries answered locally (hub, peer or
 * resource — an authoritative hit, including a zone NXDOMAIN) versus
 * forwarded to upstream — in-memory only, reset by a restart, same "a live
 * sample, not a DB-backed stat" posture as {@code HostHealthSampler}. One
 * bucket per wall-clock minute rather than a true sliding window: cheap, and
 * nobody needs the dashboard tile accurate to the second.
 */
@ApplicationScoped
public class DnsQueryStats {

    private static final long WINDOW_MINUTES = 30;

    private final ConcurrentHashMap<Long, AtomicLong> local = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> forwarded = new ConcurrentHashMap<>();

    public void recordLocal() {
        increment(local);
    }

    public void recordForwarded() {
        increment(forwarded);
    }

    private void increment(ConcurrentHashMap<Long, AtomicLong> buckets) {
        long minute = currentMinute();
        buckets.computeIfAbsent(minute, k -> new AtomicLong()).incrementAndGet();
        // Opportunistic prune on write — at most WINDOW_MINUTES+1 keys ever
        // accumulate, so this never scans more than that regardless of query
        // volume between prunes.
        buckets.keySet().removeIf(m -> minute - m >= WINDOW_MINUTES);
    }

    public Snapshot snapshot() {
        long minute = currentMinute();
        return new Snapshot(sumWithin(local, minute), sumWithin(forwarded, minute), WINDOW_MINUTES);
    }

    /** Queries per minute (local and forwarded summed) for the last {@code minutes}
     *  minutes, oldest first, empty minutes as 0 — the mini graph on the dashboard
     *  tile. Capped at the window the buckets are kept for. */
    public java.util.List<Long> series(int minutes) {
        int n = (int) Math.min(Math.max(minutes, 0), WINDOW_MINUTES);
        long now = currentMinute();
        java.util.List<Long> out = new java.util.ArrayList<>(n);
        for (long m = now - n + 1; m <= now; m++) {
            out.add(countAt(local, m) + countAt(forwarded, m));
        }
        return out;
    }

    private static long countAt(ConcurrentHashMap<Long, AtomicLong> buckets, long minute) {
        AtomicLong v = buckets.get(minute);
        return v == null ? 0 : v.get();
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

    public record Snapshot(long local, long forwarded, long windowMinutes) {}
}
