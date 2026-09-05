package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that keeps the discovery chain from asking mDNS and LLMNR questions
 * that a responder is required to ignore (RFC 6762 §11, RFC 4795 §2.5).
 */
class LinkScopeTest {

    @Test
    void addressInAnAttachedSubnet_isOnLink() {
        LinkScope scope = LinkScope.of(List.of("192.168.1.5/24"));

        assertThat(scope.isOnLink("192.168.1.20")).isTrue();
    }

    @Test
    void addressBehindASiteGateway_isNotOnLink() {
        // The hub's tunnel address and a host in a remote site's LAN — the exact
        // shape that made LLMNR/mDNS silent in the field.
        LinkScope scope = LinkScope.of(List.of("10.77.140.1/24"));

        assertThat(scope.isOnLink("10.83.1.132")).isFalse();
    }

    @Test
    void anyOfSeveralInterfacesCounts() {
        LinkScope scope = LinkScope.of(List.of("10.77.140.1/24", "192.168.8.3/24"));

        assertThat(scope.isOnLink("192.168.8.99")).isTrue();
    }

    @Test
    void prefixIsHonouredDownToTheBit() {
        LinkScope scope = LinkScope.of(List.of("10.0.0.1/28"));

        assertThat(scope.isOnLink("10.0.0.14")).isTrue();
        assertThat(scope.isOnLink("10.0.0.17")).isFalse();
    }

    @Test
    void aWidePrefixSwallowsEverythingInsideIt() {
        // Documented limit: the target's own netmask is unknowable from here, so
        // a /8 on the wg interface gives up the saving rather than guessing.
        LinkScope scope = LinkScope.of(List.of("10.0.0.1/8"));

        assertThat(scope.isOnLink("10.83.1.132")).isTrue();
    }

    @Test
    void ipv4AndIpv6DoNotMatchEachOther() {
        LinkScope scope = LinkScope.of(List.of("192.168.1.5/24"));

        assertThat(scope.isOnLink("fd00::1")).isFalse();
    }

    @Test
    void withNoKnownSubnets_itFailsOpen() {
        // No knowledge must cost time, not names.
        LinkScope scope = LinkScope.of(List.of());

        assertThat(scope.isOnLink("10.83.1.132")).isTrue();
    }

    @Test
    void anUnparseableAddress_failsOpen() {
        LinkScope scope = LinkScope.of(List.of("192.168.1.5/24"));

        assertThat(scope.isOnLink("not-an-address")).isTrue();
    }
}
