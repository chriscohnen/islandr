package de.chriscohnen.islandr.dns;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code upstreamSocket()} — the socket-reuse fix for {@code forward()} and
 * {@code queryUpstreamForPreview()} (dns-resolver-forward-socket-reuse):
 * both used to open and close a fresh {@link DatagramSocket} per forwarded
 * query, a real create/destroy syscall pair under actual query volume. Test
 * targets like real production addresses (RFC 5737 TEST-NET-3) — creating
 * the socket never sends anything, so no real traffic is involved.
 */
@QuarkusTest
class DnsResolverServiceTest {

    @Inject DnsResolverService service;

    @Test
    void upstreamSocket_reusesTheSameSocket_forTheSameUpstream() throws Exception {
        DatagramSocket first = service.upstreamSocket("203.0.113.1");
        try {
            DatagramSocket second = service.upstreamSocket("203.0.113.1");
            assertThat(second).isSameAs(first);
        } finally {
            first.close();
        }
    }

    @Test
    void upstreamSocket_opensADistinctSocket_perUpstream() throws Exception {
        DatagramSocket a = service.upstreamSocket("203.0.113.2");
        DatagramSocket b = service.upstreamSocket("203.0.113.3");
        try {
            assertThat(a).isNotSameAs(b);
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void upstreamSocket_opensAFreshSocket_onceThePreviousOneWasClosed() throws Exception {
        DatagramSocket first = service.upstreamSocket("203.0.113.4");
        first.close();
        DatagramSocket second = service.upstreamSocket("203.0.113.4");
        try {
            assertThat(second).isNotSameAs(first);
            assertThat(second.isClosed()).isFalse();
        } finally {
            second.close();
        }
    }
}
