package de.chriscohnen.islandr.peer;

import java.time.Duration;
import java.time.Instant;

/**
 * Derived connection state for a peer, based on {@link Peer#lastSeenAt} (last
 * WireGuard handshake observed by {@link ActivityPoller}). Independent of
 * {@link Peer#enabled} — the frontend renders a separate "disabled" badge for
 * disabled peers and only consults this enum when a peer is enabled.
 */
public enum PeerConnectionStatus {
    /** Handshake seen within {@link #CONNECTED_WINDOW}. */
    CONNECTED,
    /** Handshake seen before {@link #CONNECTED_WINDOW} but within {@link #STALE_WINDOW} —
     *  the tunnel is likely still up, but the peer has gone quiet. */
    STALE,
    /** No handshake ever, or none within {@link #STALE_WINDOW}. */
    DISCONNECTED;

    public static final Duration CONNECTED_WINDOW = Duration.ofMinutes(5);
    public static final Duration STALE_WINDOW = Duration.ofHours(24);

    public static PeerConnectionStatus of(Instant lastSeenAt, Instant now) {
        if (lastSeenAt == null) {
            return DISCONNECTED;
        }
        Duration age = Duration.between(lastSeenAt, now);
        if (age.compareTo(CONNECTED_WINDOW) < 0) {
            return CONNECTED;
        }
        if (age.compareTo(STALE_WINDOW) < 0) {
            return STALE;
        }
        return DISCONNECTED;
    }
}
