package de.chriscohnen.islandr.hosthealth;

import java.time.Instant;

public final class HostHealthDto {

    private HostHealthDto() {}

    /** "ok" | "high" | "critical" | "unavailable" (no /proc — e.g. non-Linux dev machine). */
    public static final class Status {
        public static final String OK = "ok";
        public static final String HIGH = "high";
        public static final String CRITICAL = "critical";
        public static final String UNAVAILABLE = "unavailable";
        private Status() {}
    }

    /**
     * One point-in-time reading (#73). {@code cpuPercent} is null on the very
     * first sample after startup — a CPU percentage needs a delta between two
     * {@code /proc/stat} snapshots, so there's nothing to report yet.
     *
     * <p>{@code memorySource}: "cgroup" when a finite cgroup v2 memory limit
     * was found and used instead of the host's own totals (the honest number
     * inside a memory-capped container — {@code /proc/meminfo} alone would
     * report the *host's* free memory, which is meaningless if the container
     * itself is about to be OOM-killed well before the host runs low); "host"
     * otherwise (bare-metal/systemd, the primary supported deployment, or a
     * container with no memory limit set).
     */
    public record Snapshot(
            Double cpuPercent,
            long memTotalBytes,
            long memUsedBytes,
            long swapTotalBytes,
            long swapUsedBytes,
            String memorySource,
            String status,
            Instant sampledAt
    ) {
        public static Snapshot unavailable() {
            return new Snapshot(null, 0, 0, 0, 0, "host", Status.UNAVAILABLE, Instant.now());
        }
    }
}
