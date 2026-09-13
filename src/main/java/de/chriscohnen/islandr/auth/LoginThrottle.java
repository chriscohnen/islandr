package de.chriscohnen.islandr.auth;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes guessing a local password expensive (issue #80).
 *
 * <p>Progressive delay, not a lockout. A hard lockout on an account is a
 * denial-of-service primitive: anyone who knows an admin's e-mail could lock
 * them out at will, which trades one attack for another. A delay costs the
 * attacker time and costs the legitimate user, who fat-fingers a password
 * twice and then gets in, almost nothing.
 *
 * <p>Two counters, and the slower of the two wins: one per account, one per
 * source address. The account counter catches a focused attack on one login;
 * the address counter catches password spraying, where each account is tried
 * only once or twice and a per-account counter would never fire.
 *
 * <p>The delay must not become the user-enumeration oracle the dummy PBKDF2
 * run in {@code AuthResource} exists to prevent, so it is keyed on the
 * submitted username whether or not such an account exists, and applied before
 * the credential check either way.
 *
 * <p>A ceiling on concurrent in-flight attempts stops the delay being
 * sidestepped by firing everything at once — a thousand parallel requests each
 * sleeping a second is not a cost to the attacker, only to the hub.
 *
 * <p>Counters live in memory and per process. Islandr is a single process, and
 * persisting them would add a write path whose rate an attacker controls.
 */
@ApplicationScoped
public class LoginThrottle {

    /** Failures answered at full speed before the delay starts. */
    @ConfigProperty(name = "islandr.auth.throttle.free-attempts", defaultValue = "2")
    int freeAttempts;

    @ConfigProperty(name = "islandr.auth.throttle.base-delay-ms", defaultValue = "250")
    long baseDelayMs;

    @ConfigProperty(name = "islandr.auth.throttle.max-delay-ms", defaultValue = "5000")
    long maxDelayMs;

    /** Quiet time after which a counter is forgotten. */
    @ConfigProperty(name = "islandr.auth.throttle.window-minutes", defaultValue = "15")
    long windowMinutes;

    @ConfigProperty(name = "islandr.auth.throttle.max-concurrent", defaultValue = "8")
    int maxConcurrent;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private volatile Semaphore inFlight;

    private static final class Counter {
        final AtomicLong failures = new AtomicLong();
        volatile long lastFailureAt;
    }

    /** How long this attempt should be held before it is answered. */
    public long delayMillis(String username, String address) {
        return Math.max(delayFor(accountKey(username)), delayFor(addressKey(address)));
    }

    public void recordFailure(String username, String address) {
        bump(accountKey(username));
        bump(addressKey(address));
        pruneIfLarge();
    }

    /** A correct password clears both counters — the address is evidently not hostile. */
    public void recordSuccess(String username, String address) {
        counters.remove(accountKey(username));
        counters.remove(addressKey(address));
    }

    /**
     * @return false when too many attempts are already in flight; the caller
     *         answers 429 rather than queueing, so the hub keeps its worker
     *         threads for everything else it has to serve.
     */
    public boolean tryEnter() {
        return semaphore().tryAcquire();
    }

    public void exit() {
        semaphore().release();
    }

    private Semaphore semaphore() {
        Semaphore s = inFlight;
        if (s == null) {
            synchronized (this) {
                if (inFlight == null) inFlight = new Semaphore(Math.max(1, maxConcurrent));
                s = inFlight;
            }
        }
        return s;
    }

    private long delayFor(String key) {
        Counter c = counters.get(key);
        if (c == null || expired(c)) return 0;
        long over = c.failures.get() - freeAttempts;
        if (over <= 0) return 0;
        // Doubling per failure past the free ones, capped. Shifting by more
        // than 62 would overflow, and the cap has long since been reached.
        long factor = over >= 32 ? Long.MAX_VALUE : 1L << (over - 1);
        if (factor > maxDelayMs) return maxDelayMs;
        return Math.min(maxDelayMs, baseDelayMs * factor);
    }

    private void bump(String key) {
        Counter c = counters.computeIfAbsent(key, k -> new Counter());
        if (expired(c)) c.failures.set(0);
        c.failures.incrementAndGet();
        c.lastFailureAt = System.currentTimeMillis();
    }

    private boolean expired(Counter c) {
        return System.currentTimeMillis() - c.lastFailureAt > windowMinutes * 60_000L;
    }

    /**
     * Bounds the map. An attacker picks the keys, so it cannot be allowed to
     * grow with every username they invent.
     */
    private void pruneIfLarge() {
        if (counters.size() < 10_000) return;
        counters.entrySet().removeIf(e -> expired(e.getValue()));
    }

    private static String accountKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "a:" + (address == null ? "unknown" : address);
    }
}
