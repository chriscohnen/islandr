package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for IPv4 CIDR host enumeration (ADR-0014, slice 3). */
class CidrHostsTest {

    @Test
    void slash24_excludesNetworkAndBroadcast() {
        var hosts = CidrHosts.hosts("10.0.0.0/24");
        assertThat(hosts).hasSize(254);
        assertThat(hosts.get(0)).isEqualTo("10.0.0.1");
        assertThat(hosts.get(253)).isEqualTo("10.0.0.254");
        assertThat(hosts).doesNotContain("10.0.0.0", "10.0.0.255");
    }

    @Test
    void slash30_hasTwoHosts() {
        assertThat(CidrHosts.hosts("192.168.1.0/30")).containsExactly("192.168.1.1", "192.168.1.2");
    }

    @Test
    void slash31_and_slash32_keepAllAddresses() {
        assertThat(CidrHosts.hosts("192.168.1.0/31")).containsExactly("192.168.1.0", "192.168.1.1");
        assertThat(CidrHosts.hosts("10.5.5.5/32")).containsExactly("10.5.5.5");
    }

    @Test
    void normalizesANonNetworkBaseToItsNetwork() {
        // 10.0.0.5/24 describes the 10.0.0.0/24 block.
        assertThat(CidrHosts.hosts("10.0.0.5/24")).isEqualTo(CidrHosts.hosts("10.0.0.0/24"));
    }

    @Test
    void rangeAboveTheCap_throws() {
        assertThatThrownBy(() -> CidrHosts.hosts("10.0.0.0/16"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void ipv6OrMalformed_throws() {
        assertThatThrownBy(() -> CidrHosts.hosts("fd00::/64")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrHosts.hosts("nonsense")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrHosts.hosts("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }
}
