package de.chriscohnen.islandr.hosthealth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for the /proc parsing and threshold logic (#73) — no
 * filesystem, no dependency on the actual OS the test happens to run on
 * (CI's real /proc would make assertions non-deterministic; a synthetic
 * line makes them exact).
 */
class HostHealthSamplerTest {

    @Test
    void parseCpuTicks_readsAggregateCpuLine() {
        // user nice system idle iowait irq softirq steal guest guest_nice
        long[] ticks = HostHealthSampler.parseCpuTicks("cpu  100 0 50 800 10 0 0 0 0 0");
        assertThat(ticks[0]).as("idleTicks = idle + iowait").isEqualTo(810);
        assertThat(ticks[1]).as("totalTicks excludes guest/guest_nice").isEqualTo(960);
    }

    @Test
    void cpuPercentFromTicks_firstSample_isNull() {
        assertThat(HostHealthSampler.cpuPercentFromTicks(100, 200, -1, -1)).isNull();
    }

    @Test
    void cpuPercentFromTicks_halfIdleDelta_is50Percent() {
        // Previous: idle=100 total=200. Now: idle=150 total=300 -> delta idle=50, delta total=100.
        Double percent = HostHealthSampler.cpuPercentFromTicks(150, 300, 100, 200);
        assertThat(percent).isEqualTo(50.0);
    }

    @Test
    void cpuPercentFromTicks_allIdleDelta_isZeroPercent() {
        Double percent = HostHealthSampler.cpuPercentFromTicks(200, 300, 100, 200);
        assertThat(percent).isEqualTo(0.0);
    }

    @Test
    void cpuPercentFromTicks_noIdleDelta_is100Percent() {
        Double percent = HostHealthSampler.cpuPercentFromTicks(100, 300, 100, 200);
        assertThat(percent).isEqualTo(100.0);
    }

    @Test
    void parseMeminfoLines_extractsKnownFields_ignoresUnitSuffix() {
        Map<String, Long> m = HostHealthSampler.parseMeminfoLines(List.of(
                "MemTotal:       16374128 kB",
                "MemFree:         1234567 kB",
                "MemAvailable:    9876543 kB",
                "SwapTotal:        2097148 kB",
                "SwapFree:         2097148 kB",
                "Buffers:           123456 kB"));
        assertThat(m.get("MemTotal")).isEqualTo(16374128L);
        assertThat(m.get("MemAvailable")).isEqualTo(9876543L);
        assertThat(m.get("SwapTotal")).isEqualTo(2097148L);
        assertThat(m.get("SwapFree")).isEqualTo(2097148L);
    }

    @Test
    void buildMemReading_noCgroupLimit_usesHostTotals() {
        Map<String, Long> meminfo = Map.of(
                "MemTotal", 1_000_000L, "MemAvailable", 400_000L,
                "SwapTotal", 200_000L, "SwapFree", 50_000L);
        HostHealthSampler.MemReading mem = HostHealthSampler.buildMemReading(meminfo, null);
        assertThat(mem.source()).isEqualTo("host");
        assertThat(mem.totalBytes()).isEqualTo(1_000_000L * 1024);
        assertThat(mem.usedBytes()).isEqualTo(600_000L * 1024);
        assertThat(mem.swapTotalBytes()).isEqualTo(200_000L * 1024);
        assertThat(mem.swapUsedBytes()).isEqualTo(150_000L * 1024);
    }

    @Test
    void buildMemReading_withCgroupLimit_prefersCgroupOverHostTotals() {
        // A container capped well below the host's own (much larger) totals —
        // the whole point of preferring cgroup numbers when present.
        Map<String, Long> meminfo = Map.of("MemTotal", 16_000_000L, "MemAvailable", 12_000_000L);
        long[] cgroupLimit = { 512L * 1024 * 1024, 400L * 1024 * 1024 }; // 512 MiB cap, 400 MiB used
        HostHealthSampler.MemReading mem = HostHealthSampler.buildMemReading(meminfo, cgroupLimit);
        assertThat(mem.source()).isEqualTo("cgroup");
        assertThat(mem.totalBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(mem.usedBytes()).isEqualTo(400L * 1024 * 1024);
    }

    @Test
    void buildSnapshot_belowAllThresholds_isOk() {
        HostHealthSampler.MemReading mem = new HostHealthSampler.MemReading(
                1000, 100, 0, 0, "host"); // 10% mem used, no swap
        HostHealthDto.Snapshot snap = HostHealthSampler.buildSnapshot(10.0, mem); // 10% cpu
        assertThat(snap.status()).isEqualTo(HostHealthDto.Status.OK);
    }

    /**
     * Regression test for a real bug found in use: the UI showed the
     * combined {@code status} right next to the CPU% number, so a
     * memory-driven "High"/"Critical" read as "CPU is high" even though CPU
     * was fine. The per-metric fields must attribute correctly — memStatus
     * critical, cpuStatus still ok — so the frontend can show it against the
     * right number.
     */
    @Test
    void buildSnapshot_memoryAboveCriticalThreshold_isCritical_attributedToMemoryNotCpu() {
        HostHealthSampler.MemReading mem = new HostHealthSampler.MemReading(
                1000, 950, 0, 0, "host"); // 95% mem used
        HostHealthDto.Snapshot snap = HostHealthSampler.buildSnapshot(5.0, mem); // 5% cpu
        assertThat(snap.status()).isEqualTo(HostHealthDto.Status.CRITICAL);
        assertThat(snap.memStatus()).isEqualTo(HostHealthDto.Status.CRITICAL);
        assertThat(snap.cpuStatus()).as("CPU itself is fine, only memory is critical").isEqualTo(HostHealthDto.Status.OK);
    }

    @Test
    void buildSnapshot_cpuAboveHighThreshold_isHigh_attributedToCpu() {
        HostHealthSampler.MemReading mem = new HostHealthSampler.MemReading(1000, 100, 0, 0, "host");
        HostHealthDto.Snapshot snap = HostHealthSampler.buildSnapshot(80.0, mem); // 80% cpu -> high, not critical
        assertThat(snap.status()).isEqualTo(HostHealthDto.Status.HIGH);
        assertThat(snap.cpuStatus()).isEqualTo(HostHealthDto.Status.HIGH);
        assertThat(snap.memStatus()).isEqualTo(HostHealthDto.Status.OK);
    }

    @Test
    void buildSnapshot_anySwapUsage_isAtLeastHigh_evenWithLowMemoryAndCpu() {
        // 60% of a present swap counts as "high" on its own lower bar (0.50),
        // even though memory/cpu alone would both read "ok" — swap usage at
        // all is itself the warning sign.
        HostHealthSampler.MemReading mem = new HostHealthSampler.MemReading(
                1000, 100, 1000, 600, "host");
        HostHealthDto.Snapshot snap = HostHealthSampler.buildSnapshot(5.0, mem);
        assertThat(snap.status()).isEqualTo(HostHealthDto.Status.HIGH);
    }

    @Test
    void buildSnapshot_zeroSwapTotal_swapNeverContributesStatus() {
        // No swap configured at all (swapTotalBytes=0) must never read as
        // "critical" from a 0/0 ratio edge case.
        HostHealthSampler.MemReading mem = new HostHealthSampler.MemReading(1000, 100, 0, 0, "host");
        HostHealthDto.Snapshot snap = HostHealthSampler.buildSnapshot(5.0, mem);
        assertThat(snap.status()).isEqualTo(HostHealthDto.Status.OK);
    }

    @Test
    void statusFor_thresholdBoundaries() {
        assertThat(HostHealthSampler.statusFor(0.749, 0.75, 0.90)).isEqualTo(HostHealthDto.Status.OK);
        assertThat(HostHealthSampler.statusFor(0.75, 0.75, 0.90)).isEqualTo(HostHealthDto.Status.HIGH);
        assertThat(HostHealthSampler.statusFor(0.899, 0.75, 0.90)).isEqualTo(HostHealthDto.Status.HIGH);
        assertThat(HostHealthSampler.statusFor(0.90, 0.75, 0.90)).isEqualTo(HostHealthDto.Status.CRITICAL);
    }

    @Test
    void worstOf_criticalBeatsHighBeatsOk() {
        assertThat(HostHealthSampler.worstOf(HostHealthDto.Status.OK, HostHealthDto.Status.HIGH, HostHealthDto.Status.OK))
                .isEqualTo(HostHealthDto.Status.HIGH);
        assertThat(HostHealthSampler.worstOf(HostHealthDto.Status.CRITICAL, HostHealthDto.Status.HIGH))
                .isEqualTo(HostHealthDto.Status.CRITICAL);
        assertThat(HostHealthSampler.worstOf(HostHealthDto.Status.OK, HostHealthDto.Status.OK))
                .isEqualTo(HostHealthDto.Status.OK);
    }
}
