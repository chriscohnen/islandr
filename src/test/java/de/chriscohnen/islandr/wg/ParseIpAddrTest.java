package de.chriscohnen.islandr.wg;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code ip -o addr show <iface>} parser without invoking ip.
 * The probe reports the interface's *network*, not the hub's own host address:
 * the settings field it pre-fills validates peer IPs against a subnet.
 */
class ParseIpAddrTest {

    @Test
    void parses_ipv4_returnsNetworkAddressNotHostAddress() {
        String out = "7: wg0    inet 10.77.140.1/24 scope global wg0\\       valid_lft forever preferred_lft forever\n";
        assertThat(RealWgAdapter.parseIfAddr(out, false)).isEqualTo("10.77.140.0/24");
    }

    @Test
    void parses_ipv4_nonByteAlignedPrefix() {
        String out = "7: wg0    inet 10.8.3.17/20 scope global wg0\n";
        assertThat(RealWgAdapter.parseIfAddr(out, false)).isEqualTo("10.8.0.0/20");
    }

    @Test
    void parses_ipv4_hostAddressAlreadyIsNetwork() {
        String out = "7: wg0    inet 192.168.4.0/24 scope global wg0\n";
        assertThat(RealWgAdapter.parseIfAddr(out, false)).isEqualTo("192.168.4.0/24");
    }

    @Test
    void parses_ipv6_compressesZeroRun() {
        String out = "7: wg0    inet6 fd11::1/64 scope global \\       valid_lft forever preferred_lft forever\n";
        assertThat(RealWgAdapter.parseIfAddr(out, true)).isEqualTo("fd11::/64");
    }

    @Test
    void parses_ipv6_ignoresLinkLocal() {
        String out = String.join("\n",
                "7: wg0    inet6 fe80::1234:5678:9abc:def0/64 scope link ",
                "7: wg0    inet6 fd11:2233::5/48 scope global ");
        assertThat(RealWgAdapter.parseIfAddr(out, true)).isEqualTo("fd11:2233::/48");
    }

    @Test
    void parses_multipleIpv4_takesFirstGlobal() {
        String out = String.join("\n",
                "7: wg0    inet 10.77.140.1/24 scope global wg0",
                "7: wg0    inet 10.99.0.1/24 scope global secondary wg0");
        assertThat(RealWgAdapter.parseIfAddr(out, false)).isEqualTo("10.77.140.0/24");
    }

    @Test
    void returnsNull_whenFamilyAbsent() {
        String out = "7: wg0    inet 10.77.140.1/24 scope global wg0\n";
        assertThat(RealWgAdapter.parseIfAddr(out, true)).isNull();
    }

    @Test
    void returnsNull_onEmptyOrGarbage() {
        assertThat(RealWgAdapter.parseIfAddr("", false)).isNull();
        assertThat(RealWgAdapter.parseIfAddr("Device \"wg9\" does not exist.", false)).isNull();
        assertThat(RealWgAdapter.parseIfAddr("7: wg0    inet notanaddress/24 scope global", false)).isNull();
    }
}
