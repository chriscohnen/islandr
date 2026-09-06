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

    @Test
    void probe_fallsBackToSystemResolver_whenNoDnsServerConfigured() {
        // No assertion on the hostname value itself (environment-dependent) —
        // this just confirms the 3-arg constructor still compiles and probe()
        // doesn't throw when dnsServerIp is implicitly null (the pre-existing
        // behavior, now routed through resolveHostname() instead of calling
        // reverseLookup() directly).
        HostProbe probe = new HostProbe(List.of(), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST);

        HostProbe.ProbeResult result = probe.probe("127.0.0.1");

        assertThat(result).isNotNull();
    }

    @Test
    void probe_fallsBackToSystemResolver_whenTargetedPtrLookupTimesOut() {
        // Port 1 on localhost: nothing listens there, so the targeted PTR
        // lookup fails fast and probe() must still return normally (not
        // throw, not hang) via the system-resolver fallback.
        HostProbe probe = new HostProbe(List.of(), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST, "127.0.0.1");

        HostProbe.ProbeResult result = probe.probe("127.0.0.1");

        assertThat(result).isNotNull();
    }

    @Test
    void probe_exposesMac_andIsNullOverLoopback() throws IOException {
        // Loopback never populates the kernel's ARP table (ARP is an L2
        // protocol between physically/virtually adjacent hosts; loopback
        // bypasses L2 entirely) — true whether or not LinkScope's real,
        // auto-detected interfaces happen to classify 127.0.0.1 as on-link,
        // so this holds regardless of the test host's own network config.
        // The precise on-link/off-link boundary itself is LinkScopeTest's
        // job, not this one — this just proves probe() now wires mac()
        // through end-to-end without throwing.
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            HostProbe probe = new HostProbe(List.of(port), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST);

            HostProbe.ProbeResult r = probe.probe("127.0.0.1");

            assertThat(r.live()).isTrue();
            assertThat(r.mac()).isNull();
        }
    }
}
