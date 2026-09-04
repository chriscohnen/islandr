package de.chriscohnen.islandr.peer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Importing an existing WireGuard peer as a site gateway (UC: "adopt an already
 * configured hub"). Before this, the import hardcoded type=client, so a branch
 * gateway already routing 192.168.50.0/24 came in as a client peer and lost the
 * routed networks — the very thing that makes it a site.
 */
@QuarkusTest
class WgImportSitePeerTest {

    @Inject PeerService peers;

    private static String key() {
        return "IMPORT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20) + "AAAAAAAAAAAAAAAA=";
    }

    @Test
    void routedCidrs_areSplitFromTheHostAddress() {
        // wg reports the peer's own /32 alongside the networks behind it.
        assertThat(PeerService.extractRoutedCidrs("10.8.0.5/32,192.168.50.0/24"))
                .isEqualTo("192.168.50.0/24");
        assertThat(PeerService.extractRoutedCidrs("10.8.0.5/32,192.168.50.0/24,10.20.0.0/16"))
                .isEqualTo("192.168.50.0/24, 10.20.0.0/16");
    }

    @Test
    void routedCidrs_null_forAPlainClientPeer() {
        assertThat(PeerService.extractRoutedCidrs("10.8.0.5/32")).isNull();
        assertThat(PeerService.extractRoutedCidrs("10.8.0.5/32,fd11::5/128")).isNull();
        assertThat(PeerService.extractRoutedCidrs(null)).isNull();
    }

    @Test
    void importAsSite_persistsTypeAndRoutedCidrs() {
        String pk = key();
        List<PeerDto.WgImportResult> res = peers.wgImport(List.of(new PeerDto.WgImportEntry(
                pk, "branch-gw", "10.8.0.201", null, "site", "192.168.77.0/24")));

        assertThat(res).singleElement()
                .extracting(PeerDto.WgImportResult::status).isEqualTo("imported");

        Peer p = Peer.find("publicKey", pk).firstResult();
        assertThat(p.isSite()).isTrue();
        assertThat(p.siteAllowedCidrs).isEqualTo("192.168.77.0/24");
        assertThat(p.userId).isNull();
    }

    @Test
    void importAsSite_withoutCidrs_isRejected() {
        assertThatThrownBy(() -> peers.wgImport(List.of(new PeerDto.WgImportEntry(
                key(), "broken-gw", "10.8.0.202", null, "site", null))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("siteAllowedCidrs");
    }

    @Test
    void importAsClient_withCidrs_isRejected() {
        assertThatThrownBy(() -> peers.wgImport(List.of(new PeerDto.WgImportEntry(
                key(), "confused", "10.8.0.203", null, "client", "192.168.77.0/24"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("siteAllowedCidrs");
    }
}
