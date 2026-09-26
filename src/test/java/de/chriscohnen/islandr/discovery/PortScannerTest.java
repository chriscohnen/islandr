package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A TCP connect() against one host across many ports. Deliberately the same
 * unprivileged mechanism the host probe already uses (ADR-0011/0014): no raw
 * socket, no capability, no sudoers entry.
 */
class PortScannerTest {

    private static final Duration FAST = Duration.ofMillis(300);

    @Test
    void findsAnOpenPortAndIgnoresAClosedOne() throws IOException {
        try (ServerSocket open = new ServerSocket(0)) {
            int openPort = open.getLocalPort();
            int closedPort = freePort();

            List<PortScanner.OpenPort> found =
                    new PortScanner(8, FAST).scan("127.0.0.1", List.of(openPort, closedPort));

            assertThat(found).extracting(PortScanner.OpenPort::port).containsExactly(openPort);
        }
    }

    /** The whole point of the feature: a number becomes a name where one exists. */
    @Test
    void namesTheServiceWhereTheTableKnowsIt() throws IOException {
        try (ServerSocket open = new ServerSocket(0)) {
            List<PortScanner.OpenPort> found =
                    new PortScanner(4, FAST).scan("127.0.0.1", List.of(open.getLocalPort()));
            // An ephemeral port has no service name — the field is absent, not invented.
            assertThat(found).singleElement().satisfies(p -> assertThat(p.service()).isNull());
        }
        assertThat(PortScanner.serviceName(22)).isEqualTo("SSH");
        assertThat(PortScanner.serviceName(3389)).isEqualTo("RDP");
    }

    /** Results are reported the moment each port answers, not at the end — that
     *  is what lets the UI fill in during a long run and a cancel keep what it
     *  already found. */
    @Test
    void streamsEachOpenPortAsItIsFound() throws IOException {
        try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0)) {
            List<Integer> streamed = new CopyOnWriteArrayList<>();
            new PortScanner(4, FAST).scan("127.0.0.1",
                    List.of(a.getLocalPort(), b.getLocalPort()),
                    () -> {}, p -> streamed.add(p.port()));

            assertThat(streamed).containsExactlyInAnyOrder(a.getLocalPort(), b.getLocalPort());
        }
    }

    /** Progress counts every port probed, open or not — a counter that only
     *  moved on a hit would sit still through a range with nothing in it. */
    @Test
    void progressCountsEveryProbedPort() throws IOException {
        try (ServerSocket open = new ServerSocket(0)) {
            AtomicInteger done = new AtomicInteger();
            List<Integer> ports = new ArrayList<>(List.of(open.getLocalPort()));
            ports.add(freePort());
            ports.add(freePort());

            new PortScanner(4, FAST).scan("127.0.0.1", ports, done::incrementAndGet, p -> {});

            assertThat(done.get()).isEqualTo(ports.size());
        }
    }

    @Test
    void resultsComeBackInPortOrder() throws IOException {
        try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0)) {
            List<Integer> ports = List.of(Math.max(a.getLocalPort(), b.getLocalPort()),
                                          Math.min(a.getLocalPort(), b.getLocalPort()));
            List<PortScanner.OpenPort> found = new PortScanner(4, FAST).scan("127.0.0.1", ports);
            assertThat(found).extracting(PortScanner.OpenPort::port).isSorted();
        }
    }

    @Test
    void anEmptyPortListScansNothing() {
        assertThat(new PortScanner(4, FAST).scan("127.0.0.1", List.of())).isEmpty();
    }

    /** An unreachable host must end the scan by timeout, not hang it. */
    @Test
    void anUnreachableHostYieldsNothingRatherThanBlocking() {
        List<PortScanner.OpenPort> found = new PortScanner(4, Duration.ofMillis(150))
                .scan("192.0.2.1", List.of(80, 443));   // TEST-NET-1, RFC 5737: never routed
        assertThat(found).isEmpty();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();   // closed again on exit, so nothing listens there
        }
    }
}
