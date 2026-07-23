package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the unprivileged {@link HostProbe} (ADR-0014, slice 1). Plain
 * JUnit — no Quarkus boot needed for pure socket logic. Probes 127.0.0.1 with an
 * injected port set so the assertions are deterministic and don't depend on the
 * ADR's fixed ports being open on the build host.
 */
class HostProbeTest {

    private static final Duration FAST = Duration.ofMillis(500);

    @Test
    void openTcpPort_reportsLiveWithThatPortOpen() throws IOException {
        // A listening socket = an open port. We never accept(); the OS completes
        // the TCP handshake from the listen backlog, so connect() succeeds.
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            HostProbe probe = new HostProbe(List.of(port), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST);

            HostProbe.ProbeResult r = probe.probe("127.0.0.1");

            assertThat(r.live()).isTrue();
            assertThat(r.openPorts()).containsExactly(port);
        }
    }

    @Test
    void closedTcpPort_stillReportsLiveButNoOpenPorts() throws IOException {
        // Bind then release a port so nothing listens there: connecting is refused,
        // which still proves the host is up (ADR-0014: open OR closed == live).
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        HostProbe probe = new HostProbe(List.of(closedPort), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST);

        HostProbe.ProbeResult r = probe.probe("127.0.0.1");

        assertThat(r.live()).isTrue();
        assertThat(r.openPorts()).isEmpty();
    }

    @Test
    void defaultTcpPortSet_matchesAdr0014() {
        assertThat(HostProbe.DEFAULT_TCP_PORTS)
                .containsExactly(22, 80, 443, 445, 554, 631, 3389, 5900, 7222, 8006, 8080, 8123, 8443, 9100, 9443);
    }
}
