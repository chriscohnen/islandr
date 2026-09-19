package de.chriscohnen.islandr.auth;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The challenge issued for a ceremony, held between the two calls it spans.
 *
 * <p>Server-side, because that is the whole point of a challenge: a value the
 * client returns is worth nothing unless the server remembers what it asked
 * for. Keeping it in a cookie or handing it back for the client to echo would
 * make the replay protection ceremonial.
 *
 * <p>In memory rather than in the database. A challenge outlives its usefulness
 * within two minutes, and a restart in the middle of a ceremony is a restarted
 * ceremony — not state worth persisting, and not state worth writing to disk
 * either.
 *
 * <p>One slot per subject and rp: starting a second ceremony replaces the
 * first, so a challenge can never be consumed twice.
 */
@ApplicationScoped
public class WebAuthnChallenges {

    /** Long enough for someone to find their key and touch it, short enough
     *  that a stolen challenge is worthless by the time it could be used. The
     *  browser's own dialog timeout is set to match. */
    static final Duration TTL = Duration.ofMinutes(2);

    private record Pending(String challenge, Instant expiresAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public void put(String subject, String rpId, String challenge) {
        prune();
        pending.put(key(subject, rpId), new Pending(challenge, Instant.now().plus(TTL)));
    }

    /**
     * Returns the pending challenge and removes it — single use, always.
     *
     * @return the challenge, or {@code null} when there is none or it expired
     */
    public String consume(String subject, String rpId) {
        Pending p = pending.remove(key(subject, rpId));
        if (p == null || p.expiresAt().isBefore(Instant.now())) return null;
        return p.challenge();
    }

    private void prune() {
        if (pending.size() < 32) return;
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private static String key(String subject, String rpId) {
        return subject + "|" + rpId;
    }
}
