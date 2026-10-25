package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traffic-window-tile: the rolling hour behind the dashboard's traffic tile.
 *
 * <p>Plain unit tests — the class holds no state worth a Quarkus boot, and the
 * one thing worth pinning down is that it counts deltas rather than totals.
 * Feeding it cumulative counters would make the tile climb forever and look
 * like traffic that never happened.
 */
class TrafficWindowStatsTest {

    @Test
    void anEmptyWindowIsZeroRatherThanAbsent() {
        var snap = new TrafficWindowStats().snapshot();
        assertThat(snap.rxBytes()).isZero();
        assertThat(snap.txBytes()).isZero();
        assertThat(snap.windowMinutes()).isEqualTo(60);
    }

    @Test
    void deltasAccumulateWithinTheWindow() {
        var stats = new TrafficWindowStats();
        stats.record(100, 50);
        stats.record(400, 150);
        var snap = stats.snapshot();
        assertThat(snap.rxBytes()).isEqualTo(500);
        assertThat(snap.txBytes()).isEqualTo(200);
    }

    /** A peer that sent nothing this tick must not create a bucket — the prune
     *  is opportunistic and runs on write, so a stream of no-op samples would
     *  otherwise be the one path that never prunes. */
    @Test
    void anIdleSampleIsNotRecordedAtAll() {
        var stats = new TrafficWindowStats();
        stats.record(0, 0);
        stats.record(-1, -1);   // a counter reset arrives clamped, but be sure
        assertThat(stats.snapshot().rxBytes()).isZero();
        assertThat(stats.snapshot().txBytes()).isZero();
    }

    @Test
    void oneDirectionAloneIsCounted() {
        var stats = new TrafficWindowStats();
        stats.record(0, 900);
        var snap = stats.snapshot();
        assertThat(snap.rxBytes()).isZero();
        assertThat(snap.txBytes()).isEqualTo(900);
    }
}
