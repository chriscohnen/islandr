package de.chriscohnen.islandr.network;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link NetworkDiagnosticsAdapter} backed by the {@code ping}/{@code tracepath}/{@code mtr}
 * CLI tools (ADR-0025).
 *
 * <p>{@code ping} needs {@code sudo} on a native install (raw ICMP, same escalation shape as
 * {@code wg}/{@code nft} per ADR-0011); {@code tracepath} needs no elevation at all on Linux
 * (UDP + Path-MTU-Discovery) and is always invoked directly, {@code sudo} or not.
 *
 * <p>A lost ping (partial or total packet loss) is a normal, successful invocation — {@code ping}
 * exits {@code 1} on 100% loss, not an error. Only "tool missing" / "process would not start" /
 * "timed out" are {@link NetworkDiagnosticsException}s; the run-tolerant-of-exit-1 handling here
 * is deliberately separate from {@code RealWgAdapter}'s throw-on-nonzero-exit helper.
 */
public class RealNetworkDiagnosticsAdapter implements NetworkDiagnosticsAdapter {

    private static final Logger LOG = Logger.getLogger(RealNetworkDiagnosticsAdapter.class);
    private static final int PING_TIMEOUT_SECONDS = 2;
    private static final int CALL_TIMEOUT_SECONDS = 15;

    private final boolean useSudo;

    public RealNetworkDiagnosticsAdapter(boolean useSudo) {
        this.useSudo = useSudo;
    }

    @Override
    public Availability checkAvailability() {
        return new Availability(commandExists("ping"), commandExists("tracepath"), commandExists("mtr"));
    }

    @Override
    public PingResult ping(String ip, int count) {
        if (!commandExists("ping")) {
            throw new NetworkDiagnosticsException("ping not found — install iputils-ping to enable this probe");
        }
        String[] command = {"ping", "-c", String.valueOf(count), "-W", String.valueOf(PING_TIMEOUT_SECONDS), ip};
        Run run = run(command, useSudo);
        // exit 0 (all replies) and exit 1 (some/all lost) both produce a parseable report;
        // anything else (exit 2 = usage/DNS/socket error) means ping never actually probed.
        if (run.exitCode != 0 && run.exitCode != 1) {
            throw new NetworkDiagnosticsException("ping exited " + run.exitCode + ": " + run.stderr.trim());
        }
        return parsePingOutput(run.stdout, count);
    }

    @Override
    public TracepathResult tracepath(String ip) {
        if (!commandExists("tracepath")) {
            throw new NetworkDiagnosticsException("tracepath not found — install iputils-tracepath to enable path diagnosis");
        }
        // Never sudo: tracepath needs no elevation on Linux (UDP probes + PMTUD).
        Run run = run(new String[]{"tracepath", ip}, false);
        if (run.exitCode != 0) {
            throw new NetworkDiagnosticsException("tracepath exited " + run.exitCode + ": " + run.stderr.trim());
        }
        return parseTracepathOutput(run.stdout);
    }

    // ── parsing (static, unit-testable without shelling out; reused by SocketNetworkDiagnosticsAdapter) ──

    private static final Pattern STATS_LINE = Pattern.compile(
            "(\\d+) packets transmitted, (\\d+) (?:packets )?received,.*?([\\d.]+)% packet loss");
    private static final Pattern RTT_LINE = Pattern.compile(
            "= ([\\d.]+)/([\\d.]+)/([\\d.]+)/([\\d.]+) ms");

    /** Parses {@code ping -c N} stdout (iputils format). Visible for tests. */
    static PingResult parsePingOutput(String output, int requestedCount) {
        int sent = requestedCount;
        int received = 0;
        double loss = 100.0;
        Matcher stats = STATS_LINE.matcher(output);
        if (stats.find()) {
            sent = Integer.parseInt(stats.group(1));
            received = Integer.parseInt(stats.group(2));
            loss = Double.parseDouble(stats.group(3));
        }
        Double min = null, avg = null, max = null, mdev = null;
        Matcher rtt = RTT_LINE.matcher(output);
        if (rtt.find()) {
            min = Double.parseDouble(rtt.group(1));
            avg = Double.parseDouble(rtt.group(2));
            max = Double.parseDouble(rtt.group(3));
            mdev = Double.parseDouble(rtt.group(4));
        }
        return new PingResult(received > 0, sent, received, loss, min, avg, max, mdev, output);
    }

    // Matches "  1:  10.0.0.1     0.234ms" and "  1?: [LOCALHOST]   pmtu 1500" style lines;
    // a hop with no reply ("no reply") yields host=null, ms=null rather than being dropped,
    // so the admin sees exactly which hop went dark.
    private static final Pattern HOP_LINE = Pattern.compile(
            "^\\s*(\\d+)\\??:\\s+(\\S+)(?:\\s+([\\d.]+)ms)?");

    /** Parses {@code tracepath} stdout. Visible for tests. */
    static TracepathResult parseTracepathOutput(String output) {
        List<TracepathHop> hops = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("     Resume:")) continue; // trailing summary line
            Matcher m = HOP_LINE.matcher(line);
            if (!m.find()) continue;
            int ttl = Integer.parseInt(m.group(1));
            String host = m.group(2);
            if ("[LOCALHOST]".equals(host) && m.group(3) == null) continue; // pmtu-only preamble line
            boolean noReply = "no".equals(host) || host.contains("reply");
            Double ms = m.group(3) != null ? Double.parseDouble(m.group(3)) : null;
            hops.add(new TracepathHop(ttl, noReply ? null : host, ms));
        }
        return new TracepathResult(hops, output);
    }

    /**
     * Dependency-free "is this on PATH" check — walks {@code $PATH} looking for an
     * executable file, the same approach a shell's own builtin {@code command -v}
     * uses. Avoids depending on {@code which} being present, which is not
     * guaranteed on every minimal install either.
     */
    static boolean commandExists(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) path = "/usr/bin:/bin:/usr/sbin:/sbin";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            File candidate = new File(dir, name);
            if (candidate.canExecute()) return true;
        }
        return false;
    }

    private record Run(int exitCode, String stdout, String stderr) {}

    private static Run run(String[] command, boolean sudo) {
        String[] effective = command;
        if (sudo) {
            effective = new String[command.length + 1];
            effective[0] = "sudo";
            System.arraycopy(command, 0, effective, 1, command.length);
        }
        ProcessBuilder pb = new ProcessBuilder(effective).redirectErrorStream(false);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new NetworkDiagnosticsException("failed to start " + command[0] + ": " + e.getMessage(), e);
        }
        String stdout = readStream(proc.getInputStream());
        String stderr = readStream(proc.getErrorStream());
        boolean finished;
        try {
            finished = proc.waitFor(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new NetworkDiagnosticsException(command[0] + " interrupted", e);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new NetworkDiagnosticsException(command[0] + " timed out after " + CALL_TIMEOUT_SECONDS + "s");
        }
        if (proc.exitValue() != 0) {
            LOG.debugf("%s exited with %d. stderr: %s", command[0], proc.exitValue(), stderr);
        }
        return new Run(proc.exitValue(), stdout, stderr);
    }

    private static String readStream(java.io.InputStream is) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new NetworkDiagnosticsException("failed to read process stream", e);
        }
        return sb.toString();
    }
}
