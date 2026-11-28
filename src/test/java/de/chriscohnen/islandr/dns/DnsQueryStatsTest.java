package de.chriscohnen.islandr.dns;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-object test of the counting logic (dns-query-stats) — no Quarkus,
 * same spirit as HostHealthSamplerTest for its own in-memory sampler. The
 * 30-minute decay itself isn't exercised here: it turns on Instant.now(),
 * and a minute-granularity boundary isn't worth a sleep-based test.
 */
class DnsQueryStatsTest {

    @Test
    void freshInstance_reportsZeroForBoth() {
        DnsQueryStats stats = new DnsQueryStats();

        DnsQueryStats.Snapshot s = stats.snapshot();

        assertThat(s.local()).isZero();
        assertThat(s.forwarded()).isZero();
        assertThat(s.windowMinutes()).isEqualTo(30);
    }

    @Test
    void countsLocalAndForwardedIndependently() {
        DnsQueryStats stats = new DnsQueryStats();

        stats.recordLocal();
        stats.recordLocal();
        stats.recordLocal();
        stats.recordForwarded();

        DnsQueryStats.Snapshot s = stats.snapshot();
        assertThat(s.local()).isEqualTo(3);
        assertThat(s.forwarded()).isEqualTo(1);
    }

    @Test
    void series_isOneNumberPerMinute_oldestFirst_emptyMinutesZero() {
        DnsQueryStats stats = new DnsQueryStats();
        stats.recordLocal();
        stats.recordLocal();
        stats.recordForwarded();

        java.util.List<Long> series = stats.series(5);

        assertThat(series).hasSize(5);
        // local and forwarded are summed; the minute may roll over mid-test,
        // so assert the total and that older minutes are flat zeros
        assertThat(series.stream().mapToLong(Long::longValue).sum()).isEqualTo(3);
        assertThat(series.get(0)).isZero();
    }

    @Test
    void series_withoutQueries_isAFlatLineOfZeros() {
        assertThat(new DnsQueryStats().series(30)).hasSize(30).containsOnly(0L);
    }

    @Test
    void series_isCappedAtTheWindow() {
        assertThat(new DnsQueryStats().series(100)).hasSize(30);
    }
}
