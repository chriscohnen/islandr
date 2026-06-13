package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IpSubnet}. Plain JUnit — no Quarkus boot needed.
 */
class IpSubnetTest {

    // ── IPv4 ─────────────────────────────────────────────────────────────────

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
    void ipv4_contains_hostsInAndOutOfSubnet() {
        IpSubnet s = IpSubnet.parse("10.8.0.0/24");
        assertThat(s.contains("10.8.0.5")).isTrue();
        assertThat(s.contains("10.8.0.255")).isTrue();
        assertThat(s.contains("10.8.1.1")).isFalse();
        assertThat(s.contains("192.168.0.1")).isFalse();
    }

    @Test
    void ipv4_contains_rejectsMixedFamily() {
        IpSubnet v4 = IpSubnet.parse("10.8.0.0/24");
        assertThat(v4.contains("fd11::3")).isFalse();
    }

    @Test
    void overlaps_identicalSubnetsCollide() {
        assertThat(IpSubnet.parse("10.8.0.0/24").overlaps(IpSubnet.parse("10.8.0.0/24"))).isTrue();
    }

    @Test
    void overlaps_supersetContainsSubset() {
        assertThat(IpSubnet.parse("10.8.0.0/16").overlaps(IpSubnet.parse("10.8.5.0/24"))).isTrue();
        assertThat(IpSubnet.parse("10.8.5.0/24").overlaps(IpSubnet.parse("10.8.0.0/16"))).isTrue();
    }

    @Test
    void overlaps_adjacentSubnetsDoNotCollide() {
        assertThat(IpSubnet.parse("10.8.0.0/24").overlaps(IpSubnet.parse("10.8.1.0/24"))).isFalse();
        assertThat(IpSubnet.parse("192.168.0.0/24").overlaps(IpSubnet.parse("10.0.0.0/8"))).isFalse();
    }

    @Test
    void ipv4_isV6_returnsFalse() {
        assertThat(IpSubnet.parse("10.8.0.0/24").isV6()).isFalse();
    }

    @Test
    void parse_invalidCidr_throws() {
        assertThatThrownBy(() -> IpSubnet.parse("not-an-ip/24"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpSubnet.parse("10.8.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/");
        assertThatThrownBy(() -> IpSubnet.parse("10.8.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────────

    @Test
    void ipv6_parse_slash64_isV6() {
        IpSubnet s = IpSubnet.parse("fd11::/64");
        assertThat(s.isV6()).isTrue();
        assertThat(s.prefix()).isEqualTo(64);
    }

    @Test
    void ipv6_contains_hostsInAndOutOfSubnet() {
        IpSubnet s = IpSubnet.parse("fd11::/64");
        assertThat(s.contains("fd11::2")).isTrue();
        assertThat(s.contains("fd11::ffff:ffff:ffff:ffff")).isTrue();
        assertThat(s.contains("fd12::1")).isFalse();
        assertThat(s.contains("::1")).isFalse();
    }

    @Test
    void ipv6_contains_rejectsMixedFamily() {
        IpSubnet v6 = IpSubnet.parse("fd11::/64");
        assertThat(v6.contains("10.8.0.5")).isFalse();
    }

    @Test
    void ipv6_assignableHostIps_slash64_startsAt2_skipsPseudoServer() {
        List<String> ips = collect(IpSubnet.parse("fd11::/64").assignableHostIps());
        // Iterating the full /64 would take forever — just check the first few entries
        assertThat(ips.get(0)).isEqualTo("fd11::2");
        assertThat(ips.get(1)).isEqualTo("fd11::3");
        assertThat(ips).doesNotContain("fd11::0", "fd11::1");
    }

    @Test
    void ipv6_assignableHostIps_slash126_returnsTwoSlots() {
        // /126 has 4 addresses: ::0 network, ::1 server, ::2, ::3. No broadcast in IPv6.
        List<String> ips = collect(IpSubnet.parse("fd00::/126").assignableHostIps());
        assertThat(ips).hasSize(2);
        assertThat(ips.get(0)).isEqualTo("fd00::2");
        assertThat(ips.get(1)).isEqualTo("fd00::3");
    }

    @Test
    void ipv6_assignableHostIps_slash127_isEmpty() {
        assertThat(collect(IpSubnet.parse("fd00::/127").assignableHostIps())).isEmpty();
    }

    @Test
    void ipv6_assignableHostIps_slash128_isEmpty() {
        assertThat(collect(IpSubnet.parse("fd00::5/128").assignableHostIps())).isEmpty();
    }

    @Test
    void ipv6_overlaps_identicalSubnets() {
        assertThat(IpSubnet.parse("fd11::/64").overlaps(IpSubnet.parse("fd11::/64"))).isTrue();
    }

    @Test
    void ipv6_overlaps_differentSubnets() {
        assertThat(IpSubnet.parse("fd11::/64").overlaps(IpSubnet.parse("fd12::/64"))).isFalse();
    }

    @Test
    void overlaps_crossFamily_neverCollide() {
        assertThat(IpSubnet.parse("10.8.0.0/24").overlaps(IpSubnet.parse("fd11::/64"))).isFalse();
    }

    @Test
    void ipv6_parse_prefixOutOfRange_throws() {
        assertThatThrownBy(() -> IpSubnet.parse("fd11::/129"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static <T> List<T> collect(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        // cap at 300: enough for the largest tested IPv4 subnet (/24 = 253 usable),
        // while still stopping early when iterating a vast IPv6 /64.
        int limit = 300;
        for (T item : it) {
            out.add(item);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
