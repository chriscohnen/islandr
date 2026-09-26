package de.chriscohnen.islandr.discovery;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Probes one host across a list of TCP ports and reports the ones that accept a
 * connection. The counterpart to {@link HostProbe}, turned ninety degrees: that
 * one asks fifteen fixed ports of many hosts to decide what a device <em>is</em>,
 * this one asks many ports of a single host to find out what it <em>offers</em>.
 *
 * <p><b>TCP only, and that is stated rather than hidden.</b> A {@code connect()}
 * cannot answer for UDP: a UDP service that never replies is indistinguishable
 * from a filtered one, so reporting "no UDP ports open" would be a claim this
 * mechanism cannot make. Unprivileged {@link Socket} throughout — no raw socket,
 * no {@code CAP_NET_RAW}, no {@code sudoers} entry (ADR-0011, ADR-0014).
 *
 * <p>A refused connection and a timeout are both "not open" here. The
 * distinction matters to a host probe deciding liveness; to an inventory of
 * services it does not.
 */
public class PortScanner {

    private final int concurrency;
    private final Duration timeout;

    public PortScanner(int concurrency, Duration timeout) {
        this.concurrency = Math.max(1, concurrency);
        this.timeout = timeout;
    }

    /**
     * One open TCP port. {@code service} is the name from the bundled table
     * ({@link PortServiceLookup}) or null — a port nobody has a name for stays a
     * number rather than acquiring a guess.
     */
    public record OpenPort(int port, String service) {}

    public List<OpenPort> scan(String ip, List<Integer> ports) {
        return scan(ip, ports, () -> {}, p -> {});
    }

    /**
     * As {@link #scan(String, List)}, reporting progress per probed port and
     * handing each open port to {@code onFound} at the moment it answers —
     * before the scan as a whole finishes, so a caller can show results while
     * the run continues and keep them if it is cancelled.
     *
     * <p>{@code onProbed} and {@code onFound} run on worker threads and may be
     * called concurrently.
     */
    public List<OpenPort> scan(String ip, List<Integer> ports, Runnable onProbed, Consumer<OpenPort> onFound) {
        if (ports.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, ports.size()));
        try {
            List<Future<OpenPort>> futures = new ArrayList<>(ports.size());
            for (int port : ports) futures.add(pool.submit(() -> {
                try {
                    if (!isOpen(ip, port)) return null;
                    OpenPort found = new OpenPort(port, serviceName(port));
                    onFound.accept(found);
                    return found;
                } finally {
                    // Counted where the probe actually finishes, not in the
                    // ordered collection loop below — otherwise one slow port
                    // near the front freezes the counter until it times out.
                    onProbed.run();
                }
            }));

            List<OpenPort> open = new ArrayList<>();
            for (Future<OpenPort> f : futures) {
                try {
                    OpenPort p = f.get();
                    if (p != null) open.add(p);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // one port's probe blew up — skip it, keep scanning
                }
            }
            open.sort(Comparator.comparingInt(OpenPort::port));
            return List.copyOf(open);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The table's name for a TCP port, or null when it has none. */
    public static String serviceName(int port) {
        return PortServiceLookup.serviceFor(port, "tcp").orElse(null);
    }

    private boolean isOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), (int) timeout.toMillis());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            // Refused, timed out, unroutable — all "not open" for an inventory.
            return false;
        }
    }
}
