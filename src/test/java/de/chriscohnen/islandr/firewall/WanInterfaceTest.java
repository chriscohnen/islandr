package de.chriscohnen.islandr.firewall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** egress-via-hub: the WAN interface is read from the kernel's routing table, not configured by hand. */
class WanInterfaceTest {

    private static final String HEADER =
            "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n";

    @Test
    void defaultRoute_namesTheInterface() {
        String table = HEADER
                + "wg0\t0000080A\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n"
                + "eth0\t00000000\t0100A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0\n";
        assertThat(WanInterface.parseDefaultRoute(table)).isEqualTo("eth0");
    }

    @Test
    void lowestMetric_winsAmongSeveralDefaultRoutes() {
        String table = HEADER
                + "wlan0\t00000000\t0101A8C0\t0003\t0\t0\t600\t00000000\t0\t0\t0\n"
                + "ens3\t00000000\t01010A0A\t0003\t0\t0\t100\t00000000\t0\t0\t0\n";
        assertThat(WanInterface.parseDefaultRoute(table)).isEqualTo("ens3");
    }

    @Test
    void noDefaultRoute_isNull() {
        String table = HEADER + "eth0\t0000A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n";
        assertThat(WanInterface.parseDefaultRoute(table)).isNull();
    }

    @Test
    void garbage_isNull() {
        assertThat(WanInterface.parseDefaultRoute("")).isNull();
        assertThat(WanInterface.parseDefaultRoute(null)).isNull();
    }
}
