package de.chriscohnen.islandr.hosthealth;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Periodically samples the hub's own CPU/memory/swap usage from {@code /proc}
 * (#73) — no system-info library, consistent with this project's
 * hand-rolled-over-library posture (a library here would also mean extra
 * GraalVM native-image reflection config for no real benefit). Only ever
 * reads files, never a shell command — same unprivileged posture as the rest
 * of the app's host introspection (ADR-0011).
 *
 * <p>A CPU percentage needs a delta between two {@code /proc/stat} snapshots,
 * so the sampler keeps its own previous reading and computes the delta on
 * every tick rather than blocking a request on two live samples.
 *
 * <p>{@code /proc} doesn't exist on a non-Linux dev machine (e.g. running
 * {@code quarkusDev} on macOS) — every read is wrapped so a missing/unreadable
 * file degrades to {@link HostHealthDto.Status#UNAVAILABLE} instead of ever
 * throwing out of the scheduled tick.
 *
 * <p>Disabled by {@code islandr.host-health.poll-enabled=false}; the test
 * profile turns it off (same reasoning as {@code ActivityPoller}: tests
 * shouldn't see a background sampler mutate shared state, and CPU
 * percentages aren't deterministic to assert on anyway).
 */
@ApplicationScoped
public class HostHealthSampler {

    private static final Logger LOG = Logger.getLogger(HostHealthSampler.class);

    private static final Path PROC_STAT = Path.of("/proc/stat");
    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");
    private static final Path CGROUP_MEM_MAX = Path.of("/sys/fs/cgroup/memory.max");
    private static final Path CGROUP_MEM_CURRENT = Path.of("/sys/fs/cgroup/memory.current");

    private static final double HIGH_THRESHOLD = 0.75;
    private static final double CRITICAL_THRESHOLD = 0.90;
    // Swap gets its own, lower bar: any real swap usage is already a sign of
    // memory pressure, well before a host is anywhere near "critical" on
    // memory alone.
    private static final double SWAP_HIGH_THRESHOLD = 0.50;
    private static final double SWAP_CRITICAL_THRESHOLD = 0.80;

    @ConfigProperty(name = "islandr.host-health.poll-enabled", defaultValue = "true")
    boolean pollEnabled;

    private volatile HostHealthDto.Snapshot latest = HostHealthDto.Snapshot.unavailable();
    private long prevIdleTicks = -1;
    private long prevTotalTicks = -1;

    public HostHealthDto.Snapshot latest() {
        return latest;
    }

    @Scheduled(every = "5s",
               identity = "islandr-host-health-sampler",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sample() {
        if (!pollEnabled) return;
        try {
            Double cpuPercent = sampleCpuPercent();
            MemReading mem = sampleMemory();
            latest = buildSnapshot(cpuPercent, mem);
        } catch (Exception e) {
            // /proc missing entirely (non-Linux dev machine) is the expected
            // case here, not a bug — log once at debug, not a warning spammed
            // every 5s.
            LOG.debugf("host-health sample skipped: %s", e.getMessage());
            latest = HostHealthDto.Snapshot.unavailable();
        }
    }

    record MemReading(long totalBytes, long usedBytes, long swapTotalBytes,
                               long swapUsedBytes, String source) {}

    private Double sampleCpuPercent() {
        try {
            String line = Files.readAllLines(PROC_STAT).stream()
                    .filter(l -> l.startsWith("cpu "))
                    .findFirst().orElse(null);
            if (line == null) return null;
            long[] ticks = parseCpuTicks(line);
            long idleTicks = ticks[0], totalTicks = ticks[1];

            Double percent = cpuPercentFromTicks(idleTicks, totalTicks, prevIdleTicks, prevTotalTicks);
            prevIdleTicks = idleTicks;
            prevTotalTicks = totalTicks;
            return percent;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Returns {@code [idleTicks, totalTicks]} from a {@code "cpu  ..."} /proc/stat line. */
    static long[] parseCpuTicks(String statLine) {
        String[] parts = statLine.trim().split("\\s+");
        // user nice system idle iowait irq softirq steal [guest guest_nice].
        // Guest time is already included in "user" per the kernel's own
        // accounting, so it's deliberately excluded from the total below to
        // avoid double-counting it.
        long user = Long.parseLong(parts[1]);
        long nice = Long.parseLong(parts[2]);
        long system = Long.parseLong(parts[3]);
        long idle = Long.parseLong(parts[4]);
        long iowait = Long.parseLong(parts[5]);
        long irq = Long.parseLong(parts[6]);
        long softirq = Long.parseLong(parts[7]);
        long steal = Long.parseLong(parts[8]);
        long idleTicks = idle + iowait;
        long totalTicks = user + nice + system + idle + iowait + irq + softirq + steal;
        return new long[] { idleTicks, totalTicks };
    }

    /** Null on the first-ever sample (no previous snapshot to delta against yet). */
    static Double cpuPercentFromTicks(long idleTicks, long totalTicks, long prevIdleTicks, long prevTotalTicks) {
        if (prevTotalTicks < 0) return null;
        long deltaIdle = idleTicks - prevIdleTicks;
        long deltaTotal = totalTicks - prevTotalTicks;
        if (deltaTotal <= 0) return null;
        return 100.0 * (1.0 - ((double) deltaIdle / deltaTotal));
    }

    private MemReading sampleMemory() throws IOException {
        Map<String, Long> meminfo = parseMeminfoLines(Files.readAllLines(PROC_MEMINFO));
        long[] cgroupLimit = readCgroupV2MemoryLimit();
        return buildMemReading(meminfo, cgroupLimit);
    }

    static Map<String, Long> parseMeminfoLines(List<String> lines) {
        Map<String, Long> out = new java.util.HashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            String[] parts = rest.split("\\s+");
            try {
                out.put(key, Long.parseLong(parts[0]));
            } catch (NumberFormatException ignored) { /* not a numeric field, skip */ }
        }
        return out;
    }

    /** {@code cgroupLimit} (nullable) is {@code [totalBytes, usedBytes]} — see {@link #readCgroupV2MemoryLimit()}. */
    static MemReading buildMemReading(Map<String, Long> meminfo, long[] cgroupLimit) {
        long memTotalKb = meminfo.getOrDefault("MemTotal", 0L);
        long memAvailKb = meminfo.getOrDefault("MemAvailable", 0L);
        long swapTotalKb = meminfo.getOrDefault("SwapTotal", 0L);
        long swapFreeKb = meminfo.getOrDefault("SwapFree", 0L);
        long swapTotalBytes = swapTotalKb * 1024;
        long swapUsedBytes = Math.max(0, (swapTotalKb - swapFreeKb) * 1024);

        if (cgroupLimit != null) {
            return new MemReading(cgroupLimit[0], cgroupLimit[1], swapTotalBytes, swapUsedBytes, "cgroup");
        }
        long memTotalBytes = memTotalKb * 1024;
        long memUsedBytes = Math.max(0, (memTotalKb - memAvailKb) * 1024);
        return new MemReading(memTotalBytes, memUsedBytes, swapTotalBytes, swapUsedBytes, "host");
    }

    /**
     * Returns {@code [totalBytes, usedBytes]} when a finite cgroup v2 memory
     * limit is set, or {@code null} when there's no cgroup (bare-metal,
     * systemd — the primary supported deployment) or the limit is
     * {@code "max"} (unlimited, same as no limit for this purpose).
     */
    private long[] readCgroupV2MemoryLimit() {
        try {
            if (!Files.isReadable(CGROUP_MEM_MAX) || !Files.isReadable(CGROUP_MEM_CURRENT)) return null;
            String maxRaw = Files.readString(CGROUP_MEM_MAX).trim();
            if ("max".equals(maxRaw)) return null;
            long total = Long.parseLong(maxRaw);
            long used = Long.parseLong(Files.readString(CGROUP_MEM_CURRENT).trim());
            return new long[] { total, used };
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    static HostHealthDto.Snapshot buildSnapshot(Double cpuPercent, MemReading mem) {
        double memRatio = mem.totalBytes() > 0 ? (double) mem.usedBytes() / mem.totalBytes() : 0;
        double swapRatio = mem.swapTotalBytes() > 0 ? (double) mem.swapUsedBytes() / mem.swapTotalBytes() : 0;

        String cpuStatus = statusFor(cpuPercent == null ? 0 : cpuPercent / 100.0, HIGH_THRESHOLD, CRITICAL_THRESHOLD);
        String memStatus = statusFor(memRatio, HIGH_THRESHOLD, CRITICAL_THRESHOLD);
        String swapStatus = mem.swapTotalBytes() > 0
                ? statusFor(swapRatio, SWAP_HIGH_THRESHOLD, SWAP_CRITICAL_THRESHOLD)
                : HostHealthDto.Status.OK;
        String overall = worstOf(cpuStatus, memStatus, swapStatus);

        return new HostHealthDto.Snapshot(cpuPercent, mem.totalBytes(), mem.usedBytes(),
                mem.swapTotalBytes(), mem.swapUsedBytes(), mem.source(), overall, Instant.now());
    }

    static String statusFor(double ratio, double high, double critical) {
        if (ratio >= critical) return HostHealthDto.Status.CRITICAL;
        if (ratio >= high) return HostHealthDto.Status.HIGH;
        return HostHealthDto.Status.OK;
    }

    static String worstOf(String... statuses) {
        for (String s : statuses) if (HostHealthDto.Status.CRITICAL.equals(s)) return HostHealthDto.Status.CRITICAL;
        for (String s : statuses) if (HostHealthDto.Status.HIGH.equals(s)) return HostHealthDto.Status.HIGH;
        return HostHealthDto.Status.OK;
    }
}
