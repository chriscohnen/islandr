package de.chriscohnen.islandr.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    public record DiscoveredHost(String ip, List<Integer> openPorts, String typeGuess) {}

    public List<DiscoveredHost> scan(List<String> ips, Function<String, HostProbe.ProbeResult> probe) {
        return scan(ips, probe, () -> {}, () -> {});
    }

    /** As {@link #scan(List, Function)}, calling {@code onHostDone} once per probed host (progress). */
    public List<DiscoveredHost> scan(List<String> ips,
                                     Function<String, HostProbe.ProbeResult> probe,
                                     Runnable onHostDone) {
        return scan(ips, probe, onHostDone, () -> {});
    }

    /**
     * As {@link #scan(List, Function, Runnable)}, additionally calling {@code onFound}
     * once for each host that probes live — so a caller can surface a running
     * "found" count while the scan is still going.
     */
    public List<DiscoveredHost> scan(List<String> ips,
                                     Function<String, HostProbe.ProbeResult> probe,
                                     Runnable onHostDone,
                                     Runnable onFound) {
        if (ips.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, ips.size()));
        try {
            List<Future<HostProbe.ProbeResult>> futures = new ArrayList<>(ips.size());
            // Count progress as each host's probe actually finishes — not in the
            // submission-ordered collection loop below, where one slow dead host
            // near the front would freeze the counter (and the UI) until it times
            // out, even though later hosts already completed.
            for (String ip : ips) futures.add(pool.submit(() -> {
                try {
                    HostProbe.ProbeResult r = probe.apply(ip);
                    if (r != null && r.live()) onFound.run();
                    return r;
                } finally {
                    onHostDone.run();
                }
            }));

            List<DiscoveredHost> live = new ArrayList<>();
            for (Future<HostProbe.ProbeResult> f : futures) {
                try {
                    HostProbe.ProbeResult r = f.get();
                    if (r != null && r.live()) {
                        live.add(new DiscoveredHost(r.ip(), r.openPorts(), TypeFingerprint.guess(r.openPorts())));
                    }
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
