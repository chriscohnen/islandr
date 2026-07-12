package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.discovery.DiscoveryScanner.DiscoveredHost;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of device-discovery scans (ADR-0014, slice 4). A scan is an
 * ephemeral async job — not persisted, swept after {@link #TTL} — so a restart
 * simply forgets in-flight scans. Bounded per site: one active scan at a time.
 *
 * <p>Mock-first (ADR-0014 §6): the default {@code mock} mode returns synthetic
 * hosts so the dev laptop and the test suite never touch a real network; the real
 * socket scan runs only when {@code islandr.discovery.mode=real}.
 */
@ApplicationScoped
public class DiscoveryJobs {

    /** Finished jobs are swept once older than this (running jobs are never swept). */
    private static final Duration TTL = Duration.ofMinutes(5);

    @ConfigProperty(name = "islandr.discovery.mode", defaultValue = "mock")
    String mode;
    @ConfigProperty(name = "islandr.discovery.timeout", defaultValue = "1s")
    Duration hostTimeout;
    @ConfigProperty(name = "islandr.discovery.concurrency", defaultValue = "64")
    int concurrency;

    private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();
    private ExecutorService pool;

    @PostConstruct
    void init() {
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "discovery-scan");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    /**
     * Whether scans hit the real network. In mock mode nothing is actually probed,
     * so route preconditions (a connected site gateway) do not apply — this keeps
     * discovery testable in Docker/dev without WireGuard (ADR-0014 §3, §6).
     */
    public boolean isRealScan() {
        return "real".equalsIgnoreCase(mode);
    }

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public static final class Job {
        public final String id;
        public final String siteId;
        public final String cidr;
        private final int total;
        private final AtomicInteger doneCount = new AtomicInteger();
        private final AtomicInteger foundCount = new AtomicInteger();
        private volatile State state = State.RUNNING;
        private volatile List<DiscoveredHost> hosts = List.of();
        private volatile String error;
        private final Instant createdAt = Instant.now();
        private volatile Future<?> future;

        Job(String id, String siteId, String cidr, int total) {
            this.id = id;
            this.siteId = siteId;
            this.cidr = cidr;
            this.total = total;
        }

        public State state() { return state; }
        public int total() { return total; }
        public int done() { return doneCount.get(); }
        public int found() { return foundCount.get(); }
        public List<DiscoveredHost> hosts() { return hosts; }
        public String error() { return error; }
    }

    /**
     * Start a scan of {@code cidr} for {@code siteId}. A scan still running for the
     * same site is superseded — cancelled, then replaced — so "scan again" always
     * yields a fresh scan and never dead-ends on a stale job (e.g. one orphaned by
     * a client that navigated away). The one-active-scan-per-site invariant, and
     * with it the connect-rate bound (ADR-0014 §4, R-142), still holds.
     *
     * @throws IllegalArgumentException the CIDR is not an enumerable IPv4 range or exceeds the cap
     */
    public Job start(String siteId, String cidr) {
        sweep();
        for (Job j : jobs.values()) {
            if (j.siteId.equals(siteId) && j.state == State.RUNNING) {
                cancel(j.id);
            }
        }
        List<String> hostIps = CidrHosts.hosts(cidr);   // may throw IllegalArgumentException
        Job job = new Job(UUID.randomUUID().toString(), siteId, cidr, hostIps.size());
        jobs.put(job.id, job);
        job.future = pool.submit(() -> run(job, hostIps));
        return job;
    }

    private void run(Job job, List<String> hostIps) {
        try {
            List<DiscoveredHost> found = "real".equalsIgnoreCase(mode)
                    ? realScan(hostIps, job.doneCount, job.foundCount)
                    : mockScan(hostIps, job.doneCount, job.foundCount);
            job.hosts = found;
            if (job.state != State.CANCELLED) job.state = State.DONE;
        } catch (Exception e) {
            job.error = e.getMessage();
            if (job.state != State.CANCELLED) job.state = State.FAILED;
        }
    }

    private List<DiscoveredHost> realScan(List<String> hostIps, AtomicInteger done, AtomicInteger found) {
        HostProbe probe = new HostProbe(HostProbe.DEFAULT_TCP_PORTS, HostProbe.DEFAULT_UDP_PROBE_PORT, hostTimeout);
        return new DiscoveryScanner(concurrency)
                .scan(hostIps, probe::probe, done::incrementAndGet, found::incrementAndGet);
    }

    /** Synthetic hosts so dev/CI never touch a real network (ADR-0014 §6). */
    private List<DiscoveredHost> mockScan(List<String> hostIps, AtomicInteger done, AtomicInteger found) {
        List<DiscoveredHost> out = new ArrayList<>();
        if (!hostIps.isEmpty()) {
            List<Integer> ports = List.of(3389, 445);
            out.add(new DiscoveredHost(hostIps.get(0), ports, TypeFingerprint.guess(ports), "mock-pc"));       // computer
        }
        if (hostIps.size() > 1) {
            List<Integer> ports = List.of(554);
            out.add(new DiscoveredHost(hostIps.get(hostIps.size() - 1), ports, TypeFingerprint.guess(ports), "mock-cam")); // camera
        }
        done.set(hostIps.size());
        found.set(out.size());
        return out;
    }

    public Job get(String jobId) {
        sweep();
        return jobs.get(jobId);
    }

    public boolean cancel(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) return false;
        job.state = State.CANCELLED;
        if (job.future != null) job.future.cancel(true);
        return true;
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(TTL);
        jobs.values().removeIf(j -> j.state != State.RUNNING && j.createdAt.isBefore(cutoff));
    }
}
