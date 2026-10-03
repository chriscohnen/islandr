package de.chriscohnen.islandr.acl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single TCP connect() against one host:port, same unprivileged mechanism
 * (and same posture, ADR-0011/0014) as {@link de.chriscohnen.islandr.discovery.PortScanner}
 * — reused instead of duplicated because a self-service reachability check has
 * no reason to need anything a port scan does not already have.
 */
class ReachabilityProbeTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Test
    void reportsLatencyForAnOpenPort() throws IOException {
        try (ServerSocket open = new ServerSocket(0)) {
            Optional<Integer> latency = ReachabilityProbe.probe("127.0.0.1", open.getLocalPort(), TIMEOUT);

            assertThat(latency).isPresent();
            assertThat(latency.get()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void isEmptyForAClosedPort() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        } // closed again immediately — nothing listens there now

        assertThat(ReachabilityProbe.probe("127.0.0.1", closedPort, TIMEOUT)).isEmpty();
    }

    @Test
    void isEmptyForAnUnreachableHostRatherThanBlocking() {
        assertThat(ReachabilityProbe.probe("192.0.2.1", 80, TIMEOUT)).isEmpty(); // TEST-NET-1, RFC 5737
    }
}
