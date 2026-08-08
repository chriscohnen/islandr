package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PeerConnectionStatusTest {

    private final Instant now = Instant.parse("2026-08-08T12:00:00Z");

    @Test
    void nullLastSeenAtIsDisconnected() {
        assertThat(PeerConnectionStatus.of(null, now)).isEqualTo(PeerConnectionStatus.DISCONNECTED);
    }

    @Test
    void recentHandshakeIsConnected() {
        Instant lastSeenAt = now.minus(Duration.ofMinutes(1));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.CONNECTED);
    }

    @Test
    void justUnderFiveMinutesIsStillConnected() {
        Instant lastSeenAt = now.minus(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.CONNECTED);
    }

    @Test
    void exactlyFiveMinutesIsStale() {
        Instant lastSeenAt = now.minus(Duration.ofMinutes(5));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.STALE);
    }

    @Test
    void twoHoursIsStale() {
        Instant lastSeenAt = now.minus(Duration.ofHours(2));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.STALE);
    }

    @Test
    void justUnder24HoursIsStillStale() {
        Instant lastSeenAt = now.minus(Duration.ofHours(23).plusMinutes(59));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.STALE);
    }

    @Test
    void exactly24HoursIsDisconnected() {
        Instant lastSeenAt = now.minus(Duration.ofHours(24));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.DISCONNECTED);
    }

    @Test
    void thirtyDaysIsDisconnected() {
        Instant lastSeenAt = now.minus(Duration.ofDays(30));
        assertThat(PeerConnectionStatus.of(lastSeenAt, now)).isEqualTo(PeerConnectionStatus.DISCONNECTED);
    }
}
