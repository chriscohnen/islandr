package de.chriscohnen.islandr.discovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of port-range scans, one per resource. Same shape and same
 * reasoning as {@link DiscoveryJobs}: an ephemeral async job, not persisted,
 * swept after {@link #TTL}, so a restart simply forgets in-flight scans.
 *
 * <p>Findings are appended as each port answers and never replaced — that is
 * what lets a cancelled run keep what it found. An admin who stops a 65535-port
 * scan halfway wants the twelve services it already turned up, not an empty
 * list.
 *
 * <p>Shares {@code islandr.discovery.mode} with device discovery rather than
 * carrying its own switch: both are "does this installation probe the real
 * network", and two flags for one question is how a dev laptop ends up scanning
 * an office LAN. Mock mode returns a fixed, plausible set without opening a
 * socket.
 */
@ApplicationScoped
public class PortScanJobs {

    /** Finished jobs are swept once older than this (running jobs are never swept). */
    private static final Duration TTL = Duration.ofMinutes(5);

    @ConfigProperty(name = "islandr.discovery.mode", defaultValue = "real")
    String mode;
    /**
     * Per-port connect timeout. Shorter than the host probe's second: a host
     * probe has to decide whether an unknown machine exists, while this one
     * already knows the target is there and is only asking about a port —
     * and it asks up to 65535 times.
     */
    @ConfigProperty(name = "islandr.port-scan.timeout", defaultValue = "400ms")
    Duration portTimeout;
    @ConfigProperty(name = "islandr.port-scan.concurrency", defaultValue = "64")
    int concurrency;

    private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();
    private ExecutorService pool;

    @PostConstruct
    void init() {
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "port-scan");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    /** Whether scans hit the real network — same switch device discovery uses. */
    public boolean isRealScan() {
        return "real".equalsIgnoreCase(mode);
    }

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public static final class Job {
        public final String id;
        public final String resourceId;
        public final String ip;
        public final String spec;
        private final int total;
        private final AtomicInteger doneCount = new AtomicInteger();
        private final List<PortScanner.OpenPort> open = new CopyOnWriteArrayList<>();
        private volatile State state = State.RUNNING;
        private volatile String error;
        private final Instant createdAt = Instant.now();
        private volatile Future<?> future;

        Job(String id, String resourceId, String ip, String spec, int total) {
            this.id = id;
            this.resourceId = resourceId;
            this.ip = ip;
            this.spec = spec;
            this.total = total;
        }

        public State state() { return state; }
        public int total() { return total; }
        public int done() { return doneCount.get(); }
        public int found() { return open.size(); }
        public String error() { return error; }

        /** Ascending by port, not in the order the probes happened to finish. */
        public List<PortScanner.OpenPort> openPorts() {
            List<PortScanner.OpenPort> snapshot = new ArrayList<>(open);
            snapshot.sort(Comparator.comparingInt(PortScanner.OpenPort::port));
            return List.copyOf(snapshot);
        }

        void addPort(PortScanner.OpenPort port) {
            open.add(port);
        }
    }

    /**
     * Start a scan of {@code spec} against {@code ip}. A scan still running for
     * the same resource is superseded — cancelled, then replaced — so "scan
     * again" always yields a fresh job and never dead-ends on one orphaned by a
     * client that navigated away.
     *
     * @throws IllegalArgumentException the port spec is malformed or out of range
     */
    public Job start(String resourceId, String ip, String spec) {
        sweep();
        // Parse before anything else: a malformed range must not leave a job
        // behind for the caller to poll, and the endpoint answers 409 from this.
        List<Integer> ports = PortRange.parse(spec);
        for (Job j : jobs.values()) {
            if (j.resourceId.equals(resourceId) && j.state == State.RUNNING) {
                cancel(j.id);
            }
        }
        Job job = new Job(UUID.randomUUID().toString(), resourceId, ip, spec, ports.size());
        jobs.put(job.id, job);
        job.future = pool.submit(() -> run(job, ports));
        return job;
    }

    private void run(Job job, List<Integer> ports) {
        try {
            if (isRealScan()) {
                new PortScanner(concurrency, portTimeout)
                        .scan(job.ip, ports, job.doneCount::incrementAndGet, job::addPort);
            } else {
                mockScan(job, ports);
            }
            if (job.state != State.CANCELLED) job.state = State.DONE;
        } catch (Exception e) {
            job.error = e.getMessage();
            if (job.state != State.CANCELLED) job.state = State.FAILED;
        }
    }

    /**
     * Synthetic results so dev and CI never open a socket. Reports only ports
     * the requested range actually covers — a mock that answered 22 for a scan
     * of 8000-8100 would teach the UI to show something the real path cannot.
     */
    private void mockScan(Job job, List<Integer> ports) {
        for (int candidate : List.of(22, 443, 3389)) {
            if (ports.contains(candidate)) {
                job.addPort(new PortScanner.OpenPort(candidate, PortScanner.serviceName(candidate)));
            }
        }
        job.doneCount.set(ports.size());
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
