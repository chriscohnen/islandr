package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IpSubnet}. Plain JUnit — no Quarkus boot needed.
 */
class IpSubnetTest {

    @Test
    void assignableHostIps_slash24_skipsNetworkServerAndBroadcast() {
        List<String> ips = collect(IpSubnet.parse("10.8.0.0/24").assignableHostIps());

        // 253 usable slots: .2 .. .254 (skip .0 network, .1 server, .255 broadcast)
        assertThat(ips).hasSize(253);
        assertThat(ips.get(0)).isEqualTo("10.8.0.2");
        assertThat(ips.get(ips.size() - 1)).isEqualTo("10.8.0.254");
        assertThat(ips).doesNotContain("10.8.0.0", "10.8.0.1", "10.8.0.255");
    }

    @Test
    void assignableHostIps_slash30_hasExactlyOneSlot() {
        // /30 has 4 addresses: .0 network, .1 server, .2 peer, .3 broadcast.
        List<String> ips = collect(IpSubnet.parse("10.0.0.0/30").assignableHostIps());
        assertThat(ips).containsExactly("10.0.0.2");
    }

    @Test
    void assignableHostIps_slash31_isEmpty() {
        // /31 (2 addresses) has no useful peer slot once the server takes one.
        assertThat(collect(IpSubnet.parse("10.0.0.0/31").assignableHostIps())).isEmpty();
    }

    @Test
    void assignableHostIps_slash32_isEmpty() {
        assertThat(collect(IpSubnet.parse("10.0.0.5/32").assignableHostIps())).isEmpty();
    }

    @Test
    void assignableHostIps_respectsNonZeroNetworkOffset() {
        // 192.168.50.0/28 → 16 addresses, hosts .1–.14, server takes .1, peers .2–.14.
        List<String> ips = collect(IpSubnet.parse("192.168.50.0/28").assignableHostIps());
        assertThat(ips).hasSize(13);
        assertThat(ips.get(0)).isEqualTo("192.168.50.2");
        assertThat(ips.get(ips.size() - 1)).isEqualTo("192.168.50.14");
    }

    @Test
    void overlaps_identicalSubnetsCollide() {
        assertThat(IpSubnet.parse("10.8.0.0/24").overlaps(IpSubnet.parse("10.8.0.0/24"))).isTrue();
    }

    @Test
    void overlaps_supersetContainsSubset() {
        // /16 contains the /24 inside it.
        assertThat(IpSubnet.parse("10.8.0.0/16").overlaps(IpSubnet.parse("10.8.5.0/24"))).isTrue();
        // Symmetry.
        assertThat(IpSubnet.parse("10.8.5.0/24").overlaps(IpSubnet.parse("10.8.0.0/16"))).isTrue();
    }

    @Test
    void overlaps_adjacentSubnetsDoNotCollide() {
        assertThat(IpSubnet.parse("10.8.0.0/24").overlaps(IpSubnet.parse("10.8.1.0/24"))).isFalse();
        assertThat(IpSubnet.parse("192.168.0.0/24").overlaps(IpSubnet.parse("10.0.0.0/8"))).isFalse();
    }

    private static <T> List<T> collect(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        it.forEach(out::add);
        return out;
    }
}
