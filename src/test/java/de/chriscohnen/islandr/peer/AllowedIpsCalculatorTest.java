package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AllowedIpsCalculator}. Plain JUnit — no Quarkus boot needed.
 */
class AllowedIpsCalculatorTest {

    @Test
    void full_ipv4Only_returnsDefaultRoute() {
        String result = AllowedIpsCalculator.compute(
                "FULL", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                null, false);
        assertThat(result).isEqualTo("0.0.0.0/0");
    }

    @Test
    void full_dualStack_includesIpv6DefaultRoute() {
        String result = AllowedIpsCalculator.compute(
                "FULL", "AUTO", null,
                "10.8.0.0/24", "fd11::/64", null, null,
                null, false);
        assertThat(result).isEqualTo("0.0.0.0/0, ::/0");
    }

    @Test
    void split_noSupernet_routesOnlyVpnSubnet() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24");
    }

    @Test
    void split_withSupernet_appendsSupernet() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, "10.0.0.0/8", null,
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.0.0/8");
    }

    @Test
    void split_dualStackWithSupernet_ordersVpnV4V6ThenSupernet() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", "fd11::/64", "10.0.0.0/8", null,
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, fd11::/64, 10.0.0.0/8");
    }

    @Test
    void split_dnsInsideRoutedRange_noExtraRouteAppended() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                "10.8.0.1", true);
        assertThat(result).isEqualTo("10.8.0.0/24");
    }

    @Test
    void split_dnsOutsideRoutedRange_appendsHostRoute() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                "10.0.5.1", true);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.5.1/32");
    }

    @Test
    void split_dnsOutsideRange_notIncluded_noRouteAppended() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                "10.0.5.1", false);
        assertThat(result).isEqualTo("10.8.0.0/24");
    }

    @Test
    void split_dnsSplitDnsSyntax_domainSuffixIgnored_ipStillRouted() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                "10.0.5.1, ~example.com", true);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.5.1/32");
    }

    @Test
    void split_dnsIpv6OutsideRange_appendsSlash128HostRoute() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", "fd11::/64", null, null,
                "fd22::1", true);
        assertThat(result).isEqualTo("10.8.0.0/24, fd11::/64, fd22::1/128");
    }

    @Test
    void full_dnsFixup_neverApplied_defaultRouteAlreadyCoversEverything() {
        String result = AllowedIpsCalculator.compute(
                "FULL", "AUTO", null,
                "10.8.0.0/24", null, null, null,
                "10.0.5.1", true);
        assertThat(result).isEqualTo("0.0.0.0/0");
    }

    @Test
    void manualMode_returnsRawValueVerbatim_ignoresEverythingElse() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "MANUAL", "192.168.99.0/24, ~weird syntax",
                "10.8.0.0/24", "fd11::/64", "10.0.0.0/8", List.of("192.168.50.0/24"),
                "10.0.5.1", true);
        assertThat(result).isEqualTo("192.168.99.0/24, ~weird syntax");
    }

    // ── site CIDRs vs. supernet coverage (issue #33 follow-up) ──────────────

    @Test
    void split_noSupernet_listsEverySiteCidrIndividually() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, null, List.of("10.20.0.0/16", "192.168.1.0/24"),
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.20.0.0/16, 192.168.1.0/24");
    }

    @Test
    void split_siteCidrInsideSupernet_omittedAsRedundant() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, "10.0.0.0/8", List.of("10.20.0.0/16"),
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.0.0/8");
    }

    @Test
    void split_siteCidrOutsideSupernet_stillListedIndividually() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, "10.0.0.0/8", List.of("192.168.1.0/24"),
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.0.0/8, 192.168.1.0/24");
    }

    @Test
    void split_mixOfCoveredAndUncoveredSites_onlyUncoveredListed() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, "10.0.0.0/8",
                List.of("10.20.0.0/16", "192.168.1.0/24", "172.16.5.0/24"),
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.0.0/8, 192.168.1.0/24, 172.16.5.0/24");
    }

    @Test
    void full_ignoresSiteCidrs_defaultRouteAlreadyCoversEverything() {
        String result = AllowedIpsCalculator.compute(
                "FULL", "AUTO", null,
                "10.8.0.0/24", null, null, List.of("192.168.1.0/24"),
                null, false);
        assertThat(result).isEqualTo("0.0.0.0/0");
    }

    @Test
    void split_malformedSupernet_failsSafeByListingAllSites() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, "not-a-cidr", List.of("10.20.0.0/16"),
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, not-a-cidr, 10.20.0.0/16");
    }

    @Test
    void split_emptySiteCidrList_noChangeFromSupernetOnly() {
        String result = AllowedIpsCalculator.compute(
                "SPLIT", "AUTO", null,
                "10.8.0.0/24", null, "10.0.0.0/8", List.of(),
                null, false);
        assertThat(result).isEqualTo("10.8.0.0/24, 10.0.0.0/8");
    }
}
