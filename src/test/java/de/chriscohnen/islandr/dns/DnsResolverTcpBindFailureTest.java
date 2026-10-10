package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dns-tcp-spin — production incident: another process (Pi-hole) already held
 * TCP port 53 on the wildcard address, so the TCP bind failed. The old code
 * assigned {@code tcp = new ServerSocket()} before {@code bind()}, so after
 * the IOException {@code tcp} was non-null — an open, unbound socket handed
 * straight to {@link DnsResolverService#tcpLoop}. {@code accept()} on an
 * unbound socket throws immediately with no syscall, the old catch block did
 * {@code continue} with no pause, and {@code isClosed()} never became true —
 * a busy loop that pinned a CPU core for 7 days before anyone noticed. CPU
 * dropped from ~90% to 1% the moment the resolver was turned off in Settings.
 *
 * <p>Runs on its own port ({@link Port}, a {@link QuarkusTestProfile} config
 * override) rather than the default 53: binding 53 needs
 * CAP_NET_BIND_SERVICE, which a test run doesn't have, so this would fail
 * before even reaching the TCP-specific scenario on the real port.
 */
@QuarkusTest
@TestProfile(DnsResolverTcpBindFailureTest.Port.class)
class DnsResolverTcpBindFailureTest {

    static final int TEST_PORT = 18053;

    public static final class Port implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("islandr.dns.port", String.valueOf(TEST_PORT));
        }
    }

    @Inject DnsResolverService resolver;

    private ServerSocket occupyingSocket;
    private String originalWgSubnet;

    @AfterEach
    void cleanup() throws IOException {
        setEnabled(false);
        resolver.reconcile();
        if (occupyingSocket != null && !occupyingSocket.isClosed()) occupyingSocket.close();
        if (originalWgSubnet != null) {
            setWgSubnet(originalWgSubnet);
        }
    }

    @Test
    void tcpBindFailure_leavesUdpRunning_keepsNoTcpSocket_startsNoTcpThread() throws IOException {
        // 127.0.0.1 is always bindable locally, unlike a real tunnel IP that
        // only exists once WireGuard is actually up — networkAddress() of a
        // /24 is the first host, so this subnet's is 127.0.0.1.
        originalWgSubnet = currentWgSubnet();
        setWgSubnet("127.0.0.0/24");

        occupyingSocket = new ServerSocket();
        occupyingSocket.setReuseAddress(true);
        occupyingSocket.bind(new InetSocketAddress("127.0.0.1", TEST_PORT));

        setEnabled(true);
        resolver.reconcile();

        try {
            assertThat(resolver.isRunning())
                    .as("UDP must still come up even though TCP's port is taken")
                    .isTrue();
            assertThat(resolver.isTcpRunning())
                    .as("the bind failed, so TCP must not report as running")
                    .isFalse();
            assertThat(resolver.hasTcpSocket())
                    .as("no socket object at all must be kept — not even an unbound one")
                    .isFalse();
            assertThat(tcpListenerThreadExists())
                    .as("tcpLoop must never be started against an unbound socket")
                    .isFalse();
        } finally {
            setEnabled(false);
            resolver.reconcile();
        }
    }

    private static boolean tcpListenerThreadExists() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "dns-resolver-tcp".equals(t.getName()));
    }

    @Transactional
    void setEnabled(boolean enabled) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverEnabled = enabled;
    }

    @Transactional
    void setWgSubnet(String subnet) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).wgSubnet = subnet;
    }

    @Transactional
    String currentWgSubnet() {
        return Settings.<Settings>findById(Settings.SINGLETON_ID).wgSubnet;
    }
}
