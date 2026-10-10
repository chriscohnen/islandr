package de.chriscohnen.islandr.acl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;

/**
 * A single TCP {@code connect()} against one host:port, timed. Backs the
 * self-service portal's on-demand "is this reachable?" check
 * ({@code myaccess-reachability-indicator}) — the browser cannot make this
 * call itself (CORS, mixed content, no raw socket against an arbitrary LAN
 * IP), so the hub does it, the same unprivileged way
 * {@link de.chriscohnen.islandr.discovery.PortScanner} already does
 * (ADR-0011/0014): no {@code CAP_NET_RAW}, nothing beyond a plain
 * {@link Socket}.
 */
final class ReachabilityProbe {

    private ReachabilityProbe() {}

    /** @return the round-trip in milliseconds, or empty when the port did not
     *  accept a connection within {@code timeout} (refused, filtered, or the
     *  host is simply not there — all the same "not reachable" here). */
    static Optional<Integer> probe(String ip, int port, Duration timeout) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), (int) Math.max(1, timeout.toMillis()));
            return Optional.of((int) ((System.nanoTime() - start) / 1_000_000));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
