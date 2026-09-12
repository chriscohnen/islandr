package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning a site gateway's routed networks into Sites. The gateway already
 * carries the authoritative CIDR list; re-typing it as Networks by hand is
 * transcription work whose typos are silent — a Site whose CIDR does not match
 * what the gateway routes grants access to nothing.
 */
@QuarkusTest
class GatewayNetworkImportTest {

    @Inject SiteService sites;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Transactional
    Peer gateway(String name, String cidrs) {
        Peer p = Peer.createNew(null, name,
                "GWKEY" + UUID.randomUUID().toString().replace("-", "").substring(0, 22) + "AAAA=",
                freeIp());
        p.type = "site";
        p.siteAllowedCidrs = cidrs;
        p.persist();
        return p;
    }

    @Transactional
    Peer clientPeer() {
        Peer p = Peer.createNew(null, "client-" + uniq(),
                "CLKEY" + UUID.randomUUID().toString().replace("-", "").substring(0, 22) + "AAAA=",
                freeIp());
        p.persist();
        return p;
    }

    private static String freeIp() {
        for (int i = 30; i < 250; i++) {
            String ip = "10.8.0." + i;
            if (Peer.find("assignedIp", ip).count() == 0) return ip;
        }
        throw new IllegalStateException("test subnet exhausted");
    }

    @Test
    void preview_listsEveryRoutedNetworkOfEverySiteGateway() {
        Peer gw = gateway("branch-" + uniq(), "192.168.71.0/24, 192.168.72.0/24");

        List<SiteDto.GatewayNetworkCandidate> found = sites.gatewayImportPreview().stream()
                .filter(c -> c.peerId().equals(gw.id)).toList();

        assertThat(found).extracting(SiteDto.GatewayNetworkCandidate::cidr)
                .containsExactly("192.168.71.0/24", "192.168.72.0/24");
        assertThat(found).allSatisfy(c -> assertThat(c.existingSiteName()).isNull());
    }

    @Test
    void preview_ignoresClientPeers() {
        Peer client = clientPeer();

        assertThat(sites.gatewayImportPreview())
                .noneMatch(c -> c.peerId().equals(client.id));
    }

    @Test
    void preview_namesTheSiteThatAlreadyCoversACidr() {
        Peer gw = gateway("dup-" + uniq(), "192.168.73.0/24");
        sites.gatewayImport(List.of(new SiteDto.GatewayNetworkEntry(
                gw.id, "192.168.73.0/24", "Already there " + uniq(), null, null)));

        SiteDto.GatewayNetworkCandidate c = sites.gatewayImportPreview().stream()
                .filter(x -> x.cidr().equals("192.168.73.0/24")).findFirst().orElseThrow();

        assertThat(c.existingSiteName()).startsWith("Already there");
    }

    @Test
    void import_createsOneSitePerNetwork_allWiredToTheGateway() {
        Peer gw = gateway("multi-" + uniq(), "192.168.74.0/24, 192.168.75.0/24");
        String n1 = "Net74 " + uniq(), n2 = "Net75 " + uniq();

        List<SiteDto.GatewayImportResult> res = sites.gatewayImport(List.of(
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.74.0/24", n1, null, null),
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.75.0/24", n2, "second", null)));

        assertThat(res).extracting(SiteDto.GatewayImportResult::status)
                .containsExactly("imported", "imported");

        Site a = Site.find("name", n1).firstResult();
        Site b = Site.find("name", n2).firstResult();
        assertThat(a.cidr).isEqualTo("192.168.74.0/24");
        assertThat(a.gatewayPeerId).isEqualTo(gw.id);
        assertThat(b.gatewayPeerId).isEqualTo(gw.id);
        assertThat(b.description).isEqualTo("second");
    }

    @Test
    void import_skipsACidrThatIsAlreadyASite_soAPartialGatewayStaysRerunnable() {
        Peer gw = gateway("partial-" + uniq(), "192.168.76.0/24, 192.168.77.0/24");
        sites.gatewayImport(List.of(new SiteDto.GatewayNetworkEntry(
                gw.id, "192.168.76.0/24", "First " + uniq(), null, null)));

        List<SiteDto.GatewayImportResult> res = sites.gatewayImport(List.of(
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.76.0/24", "Again " + uniq(), null, null),
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.77.0/24", "Second " + uniq(), null, null)));

        assertThat(res).extracting(SiteDto.GatewayImportResult::status)
                .containsExactly("skipped", "imported");
    }

    @Test
    void import_refusesAPeerThatIsNotAGateway() {
        Peer client = clientPeer();

        assertThatThrownBy(() -> sites.gatewayImport(List.of(new SiteDto.GatewayNetworkEntry(
                client.id, "192.168.78.0/24", "Nope " + uniq(), null, null))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not a site peer");
    }

    @Test
    void suggestedName_isUniquePerNetwork_sinceSiteNamesMustBe() {
        String name = "sugg-" + uniq();
        Peer gw = gateway(name, "192.168.79.0/24, 192.168.80.0/24");

        List<String> suggestions = sites.gatewayImportPreview().stream()
                .filter(c -> c.peerId().equals(gw.id))
                .map(SiteDto.GatewayNetworkCandidate::suggestedName).toList();

        assertThat(suggestions).doesNotHaveDuplicates();
        assertThat(suggestions).allMatch(s -> s.startsWith(name));
    }

    /**
     * Issue #74: a network imported from a gateway has no DNS server, and the
     * hub's own resolver knows nothing about a network reached through one —
     * so both reverse-DNS steps of a discovery scan are dead and every host
     * that reports no name of its own imports as "computer-<octet>". The
     * import dialog asks once per gateway; this is the part that has to store
     * it on every network that gateway creates.
     */
    @Test
    void importCarriesTheDnsServerOntoEveryNetworkOfThatGateway() {
        Peer gw = gateway("dns-" + uniq(), "192.168.81.0/24, 192.168.82.0/24");
        String n1 = "DnsOne " + uniq();
        String n2 = "DnsTwo " + uniq();

        sites.gatewayImport(List.of(
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.81.0/24", n1, null, "192.168.81.1"),
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.82.0/24", n2, null, "192.168.81.1")));

        assertThat(Site.<Site>find("name", n1).firstResult().dnsServerIp).isEqualTo("192.168.81.1");
        assertThat(Site.<Site>find("name", n2).firstResult().dnsServerIp).isEqualTo("192.168.81.1");
    }

    /** Left empty it stays empty — the field is optional and never guessed. */
    @Test
    void importWithoutADnsServerLeavesTheFieldEmpty() {
        Peer gw = gateway("nodns-" + uniq(), "192.168.83.0/24");
        String name = "NoDns " + uniq();

        sites.gatewayImport(List.of(
                new SiteDto.GatewayNetworkEntry(gw.id, "192.168.83.0/24", name, null, null)));

        assertThat(Site.<Site>find("name", name).firstResult().dnsServerIp).isNull();
    }
}
