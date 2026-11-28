package de.chriscohnen.islandr.settings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The DNS line of a client config: the hub's resolver first, never twice. */
class EffectiveClientDnsTest {

    private static Settings settings(boolean resolver, String clientDns) {
        Settings s = new Settings();
        s.wgSubnet = "10.197.136.0/24";
        s.dnsResolverEnabled = resolver;
        s.wgClientDns = clientDns;
        return s;
    }

    @Test
    void resolverOff_isTheFieldVerbatim() {
        assertThat(settings(false, "1.1.1.1").effectiveClientDns()).isEqualTo("1.1.1.1");
    }

    @Test
    void resolverOn_putsTheHubFirst_beforeWhatTheAdminTyped() {
        assertThat(settings(true, "1.1.1.1").effectiveClientDns()).isEqualTo("10.197.136.1, 1.1.1.1");
        assertThat(settings(true, null).effectiveClientDns()).isEqualTo("10.197.136.1");
    }

    @Test
    void hubAddressAlreadyInTheField_isNotAddedAgain() {
        assertThat(settings(true, "10.197.136.1, lab.example.de").effectiveClientDns())
                .isEqualTo("10.197.136.1, lab.example.de");
        assertThat(settings(true, "1.1.1.1 10.197.136.1").effectiveClientDns())
                .isEqualTo("1.1.1.1 10.197.136.1");
        // a longer address that merely starts with the hub's is not the hub
        assertThat(settings(true, "10.197.136.10").effectiveClientDns()).isEqualTo("10.197.136.1, 10.197.136.10");
    }
}
