package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.dns.NetBiosLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void probe_exposesMac_whenHostIsOnLinkAndArpEntryExists(@TempDir Path tmp) throws IOException {
        // Positive-path counterpart to probe_exposesMac_andIsNullOverLoopback above:
        // that test only proves mac() is null over loopback, which would also pass if
        // resolveMac() always returned null unconditionally. Here we inject a
        // LinkScope that deterministically treats 127.0.0.1 as on-link (rather than
        // relying on the build host's real, auto-detected interfaces) and an ArpCache
        // fixture keyed to 127.0.0.1, then run a live loopback probe and assert the
        // fixture's MAC comes back through end-to-end.
        String fixtureMac = "aa:bb:cc:dd:ee:ff";
        Path arpFile = tmp.resolve("arp");
        Files.writeString(arpFile,
                "IP address       HW type     Flags       HW address            Mask     Device\n"
              + "127.0.0.1        0x1         0x2         " + fixtureMac + "     *        lo\n");

        LinkScope onLinkLoopback = LinkScope.of(List.of("127.0.0.1/32"));
        ArpCache fixtureArp = new ArpCache(arpFile);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            HostProbe probe = new HostProbe(List.of(port), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST, null,
                    onLinkLoopback, fixtureArp);

            HostProbe.ProbeResult r = probe.probe("127.0.0.1");

            assertThat(r.live()).isTrue();
            assertThat(r.mac()).isEqualTo(fixtureMac);
        }
    }

    @Test
    void probe_takesTheMacFromNetbios_whenTheHostIsOffLinkAndArpHasNothing(@TempDir Path tmp) throws IOException {
        // The reach ARP cannot have (issue #76): a resource behind a site
        // gateway is never on-link, so the kernel neighbor table has nothing —
        // but its NBSTAT answer carries the MAC anyway.
        HostProbe.NodeStatusLookup netbios =
                (ip, budget) -> Optional.of(new NetBiosLookup.NodeStatus("NAS-BASEMENT", "00:1a:2b:3c:4d:5e"));

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            HostProbe probe = new HostProbe(List.of(port), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST, null,
                    LinkScope.of(List.of("10.99.0.0/24")), new ArpCache(tmp.resolve("no-arp-table")), netbios);

            HostProbe.ProbeResult r = probe.probe("127.0.0.1");

            assertThat(r.mac()).isEqualTo("00:1a:2b:3c:4d:5e");
        }
    }

    @Test
    void probe_prefersTheArpMac_overTheNetbiosOne_forOnLinkHosts(@TempDir Path tmp) throws IOException {
        // The kernel's own neighbor entry is first-hand L2 truth; NBSTAT's
        // UNIT_ID is whatever the host reports about itself. On-link, ARP wins.
        Path arpFile = tmp.resolve("arp");
        Files.writeString(arpFile,
                "IP address       HW type     Flags       HW address            Mask     Device\n"
              + "127.0.0.1        0x1         0x2         aa:bb:cc:dd:ee:ff     *        lo\n");
        HostProbe.NodeStatusLookup netbios =
                (ip, budget) -> Optional.of(new NetBiosLookup.NodeStatus("NAS-BASEMENT", "00:1a:2b:3c:4d:5e"));

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            HostProbe probe = new HostProbe(List.of(port), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST, null,
                    LinkScope.of(List.of("127.0.0.1/32")), new ArpCache(arpFile), netbios);

            HostProbe.ProbeResult r = probe.probe("127.0.0.1");

            assertThat(r.mac()).isEqualTo("aa:bb:cc:dd:ee:ff");
        }
    }

    @Test
    void probe_queriesNbstatOnlyOnce_forNameAndMacTogether(@TempDir Path tmp) throws IOException {
        // Name and MAC come out of the same NBSTAT response (RFC 1002
        // §4.2.18) — asking twice would double the per-host UDP cost of a
        // sweep for nothing.
        AtomicInteger calls = new AtomicInteger();
        HostProbe.NodeStatusLookup netbios = (ip, budget) -> {
            calls.incrementAndGet();
            return Optional.of(new NetBiosLookup.NodeStatus("NAS-BASEMENT", "00:1a:2b:3c:4d:5e"));
        };

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            HostProbe probe = new HostProbe(List.of(port), HostProbe.DEFAULT_UDP_PROBE_PORT, FAST, null,
                    LinkScope.of(List.of("10.99.0.0/24")), new ArpCache(tmp.resolve("no-arp-table")), netbios);

            HostProbe.ProbeResult r = probe.probe("127.0.0.1");

            assertThat(r.mac()).isEqualTo("00:1a:2b:3c:4d:5e");
            assertThat(calls.get()).isEqualTo(1);
        }
    }
}
