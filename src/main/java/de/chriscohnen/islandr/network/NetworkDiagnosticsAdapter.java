package de.chriscohnen.islandr.network;

import java.util.List;

/**
 * Wraps the on-demand ping/path-latency probe (ADR-0025) — a hub-originated
 * {@code ping}/{@code tracepath} against a known Resource, shelled out the same
 * way {@link de.chriscohnen.islandr.wg.WgAdapter} shells out to {@code wg}/{@code nft}.
 *
 * <p>Three implementations, same split as {@code WgAdapter}:
 * <ul>
 *   <li>{@link RealNetworkDiagnosticsAdapter} — shells out to {@code ping}/{@code tracepath}/
 *       {@code mtr} via ProcessBuilder. Production path on a native install.
 *   <li>{@link MockNetworkDiagnosticsAdapter} — deterministic in-memory implementation for
 *       tests and {@code islandr.diag.mode=mock} dev.
 *   <li>{@link SocketNetworkDiagnosticsAdapter} — talks to the host {@code islandr-proxy}
 *       over the Unix socket (ADR-0012), for the {@code socket} runtime mode.
 * </ul>
 *
 * <p>Selection happens via {@code islandr.diag.mode}; see {@code NetworkDiagnosticsAdapterProducer}.
 */
public interface NetworkDiagnosticsAdapter {

    /** Which diagnostic tools are actually present — checked, never assumed (ADR-0025 §2). */
    record Availability(boolean ping, boolean tracepath, boolean mtr) {}

    /**
     * Result of {@code ping -c <count> -W <timeout> <ip>}. {@code min/avg/max/mdevMs} are
     * {@code null} when every probe was lost (nothing to average).
     */
    record PingResult(
            boolean reachable, int sent, int received, double lossPercent,
            Double minMs, Double avgMs, Double maxMs, Double mdevMs, String rawOutput) {}

    /** One hop of a {@code tracepath} run. {@code host}/{@code ms} are null for a lost probe. */
    record TracepathHop(int ttl, String host, Double ms) {}

    record TracepathResult(List<TracepathHop> hops, String rawOutput) {}

    /** Probe {@code PATH} for {@code ping}/{@code tracepath}/{@code mtr} — never assumed present. */
    Availability checkAvailability();

    /**
     * Reachability + RTT sample against {@code ip}. {@code count} is a server-side
     * bound (ADR-0025 R-183) — callers must not pass an admin-controlled value straight
     * through without capping it first.
     *
     * @throws NetworkDiagnosticsException if {@code ping} is not available or the
     *         invocation itself fails (distinct from a reachable-but-lossy result,
     *         which is a normal {@link PingResult} with {@code reachable=false}).
     */
    PingResult ping(String ip, int count);

    /**
     * Per-hop path trace against {@code ip}.
     *
     * @throws NetworkDiagnosticsException if {@code tracepath} is not available or the
     *         invocation itself fails.
     */
    TracepathResult tracepath(String ip);
}
