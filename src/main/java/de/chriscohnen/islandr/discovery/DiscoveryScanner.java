package de.chriscohnen.islandr.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the {@link HostProbe} over a list of IPs with bounded concurrency and
 * returns the live hosts, each tagged with a {@link TypeFingerprint} guess
 * (ADR-0014, slice 3). The probe is injected as a function so the scanner is unit
 * tested without touching the network; production passes {@code hostProbe::probe}.
 */
public class DiscoveryScanner {

    private final int concurrency;

    public DiscoveryScanner(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    public record DiscoveredHost(String ip, List<Integer> openPorts, String typeGuess, String hostname, String mac) {}

    public List<DiscoveredHost> scan(List<String> ips, Function<String, HostProbe.ProbeResult> probe) {
        return scan(ips, probe, () -> {}, h -> {});
    }

    /** As {@link #scan(List, Function)}, calling {@code onHostDone} once per probed host (progress). */
    public List<DiscoveredHost> scan(List<String> ips,
                                     Function<String, HostProbe.ProbeResult> probe,
                                     Runnable onHostDone) {
        return scan(ips, probe, onHostDone, h -> {});
    }

    /**
     * As {@link #scan(List, Function, Runnable)}, additionally handing each live
     * host to {@code onFound} at the moment its own probe finishes — before the
     * scan as a whole is done, so a caller can surface the hosts themselves while
     * the sweep is still running rather than only a count (issue #75).
     *
     * <p>{@code onFound} runs on a worker thread and may be called concurrently:
     * whatever it writes into has to tolerate that.
     */
    public List<DiscoveredHost> scan(List<String> ips,
                                     Function<String, HostProbe.ProbeResult> probe,
                                     Runnable onHostDone,
                                     Consumer<DiscoveredHost> onFound) {
        if (ips.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, ips.size()));
        try {
            List<Future<DiscoveredHost>> futures = new ArrayList<>(ips.size());
            // Count progress as each host's probe actually finishes — not in the
            // submission-ordered collection loop below, where one slow dead host
            // near the front would freeze the counter (and the UI) until it times
            // out, even though later hosts already completed.
            // The DiscoveredHost is assembled here, in the worker, rather than in
            // the collection loop below: that is the only point at which a single
            // host is finished, and onFound has to hand out the host itself.
            for (String ip : ips) futures.add(pool.submit(() -> {
                try {
                    HostProbe.ProbeResult r = probe.apply(ip);
                    if (r == null || !r.live()) return null;
                    DiscoveredHost host = new DiscoveredHost(r.ip(), r.openPorts(),
                            TypeFingerprint.guess(r.openPorts()), r.hostname(), r.mac());
                    onFound.accept(host);
                    return host;
                } finally {
                    onHostDone.run();
                }
            }));

            List<DiscoveredHost> live = new ArrayList<>();
            for (Future<DiscoveredHost> f : futures) {
                try {
                    DiscoveredHost host = f.get();
                    if (host != null) live.add(host);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // one host's probe blew up — skip it, keep scanning
                }
            }
            live.sort(Comparator.comparingLong(h -> CidrHosts.ipv4ToLong(h.ip())));
            return live;
        } finally {
            pool.shutdownNow();
        }
    }
}
