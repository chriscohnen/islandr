package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the scan dialog tells the operator about its own name/MAC sources
 * before it finds anything (issue #79). Pure derivation from the site and the
 * hub's own situation — no probing, no network.
 */
class ScanSourcesTest {

    private static Map<String, DiscoveryDto.NameSource> byId(List<DiscoveryDto.NameSource> sources) {
        return sources.stream().collect(Collectors.toMap(DiscoveryDto.NameSource::id, Function.identity()));
    }

    private static ArpCache readableArp(Path dir) throws IOException {
        Path file = dir.resolve("arp");
        Files.writeString(file, "IP address       HW type     Flags       HW address            Mask     Device\n");
        return new ArpCache(file);
    }

    @Test
    void networkBehindAGateway_reportsTheLinkScopedSourcesAsOffLink(@TempDir Path tmp) throws IOException {
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "10.20.30.0/24", null, true, LinkScope.of(List.of("192.168.178.4/24")), readableArp(tmp));

        Map<String, DiscoveryDto.NameSource> s = byId(sources);
        assertThat(s.get("mdns").active()).isFalse();
        assertThat(s.get("mdns").reason()).isEqualTo("off_link");
        assertThat(s.get("llmnr").active()).isFalse();
        assertThat(s.get("arp").active()).isFalse();
        assertThat(s.get("arp").reason()).isEqualTo("off_link");
    }

    @Test
    void networkBehindAGateway_stillReportsTheUnicastSourcesAsActive(@TempDir Path tmp) throws IOException {
        // The point of the display: a remote network is not a dead end. NetBIOS
        // and SSDP are ordinary unicast and reach it; NetBIOS also carries the MAC.
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "10.20.30.0/24", null, true, LinkScope.of(List.of("192.168.178.4/24")), readableArp(tmp));

        Map<String, DiscoveryDto.NameSource> s = byId(sources);
        assertThat(s.get("netbios").active()).isTrue();
        assertThat(s.get("ssdp").active()).isTrue();
        assertThat(s.get("ptr_system").active()).isTrue();
    }

    @Test
    void hubsOwnNetwork_reportsEverySourceActive(@TempDir Path tmp) throws IOException {
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "192.168.178.0/24", "192.168.178.1", true,
                LinkScope.of(List.of("192.168.178.4/24")), readableArp(tmp));

        assertThat(sources).allMatch(DiscoveryDto.NameSource::active);
    }

    @Test
    void siteWithoutItsOwnDnsServer_reportsTheTargetedPtrLookupAsUnavailable(@TempDir Path tmp) throws IOException {
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "192.168.178.0/24", "  ", true, LinkScope.of(List.of("192.168.178.4/24")), readableArp(tmp));

        DiscoveryDto.NameSource ptr = byId(sources).get("ptr_site");
        assertThat(ptr.active()).isFalse();
        assertThat(ptr.reason()).isEqualTo("no_site_dns");
    }

    @Test
    void hostWithoutAReadableArpTable_reportsArpAsUnavailable(@TempDir Path tmp) {
        // /proc/net/arp is Linux-only — on a macOS dev box or in CI the source
        // is simply not there, which is a different reason from being off-link.
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "192.168.178.0/24", "192.168.178.1", true,
                LinkScope.of(List.of("192.168.178.4/24")), new ArpCache(tmp.resolve("absent")));

        DiscoveryDto.NameSource arp = byId(sources).get("arp");
        assertThat(arp.active()).isFalse();
        assertThat(arp.reason()).isEqualTo("no_arp_table");
    }

    @Test
    void mockMode_reportsEverySourceOff(@TempDir Path tmp) throws IOException {
        // Dev/test default: the scan returns canned hosts and touches no network
        // at all. Saying so beats listing sources that will never run.
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "192.168.178.0/24", "192.168.178.1", false,
                LinkScope.of(List.of("192.168.178.4/24")), readableArp(tmp));

        assertThat(sources).noneMatch(DiscoveryDto.NameSource::active);
        assertThat(sources).allMatch(s -> "mock_mode".equals(s.reason()));
    }

    @Test
    void sourcesAreListedInResolutionOrder(@TempDir Path tmp) throws IOException {
        // The list doubles as documentation of the chain, so its order has to be
        // the order HostProbe actually asks in.
        List<DiscoveryDto.NameSource> sources = ScanSources.forSite(
                "192.168.178.0/24", "192.168.178.1", true,
                LinkScope.of(List.of("192.168.178.4/24")), readableArp(tmp));

        assertThat(sources).extracting(DiscoveryDto.NameSource::id)
                .containsExactly("ptr_site", "ptr_system", "mdns", "llmnr", "netbios", "ssdp", "arp");
    }
}
