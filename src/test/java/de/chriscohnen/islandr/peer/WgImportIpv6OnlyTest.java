package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Found while testing 0.22.0-rc.2 on a live hub: a peer whose AllowedIPs held
 * only {@code fd11:5ee:bad:c0de::ac5:8802/128} was listed as importable, with
 * an empty IP column and no explanation. Selecting it could only ever fail —
 * {@code WgImportEntry.assignedIp} is {@code @NotBlank}, and the import writes
 * no IPv6 at all — so the dialog was offering an action guaranteed to 400.
 *
 * <p>The peer still belongs in the list: it is on the interface and Islandr
 * does not manage it, which is exactly what the dialog is for. It just must
 * not be offered.
 */
@QuarkusTest
class WgImportIpv6OnlyTest {

    @Inject PeerService peers;
    @Inject WgAdapter wg;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "islandr.wg.interface")
    String iface;

    private String uniqueKey(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20) + "AAAA=";
    }

    private PeerDto.WgImportCandidate candidateFor(String publicKey) {
        return peers.wgImportPreview().stream()
                .filter(c -> c.publicKey().equals(publicKey))
                .findFirst().orElseThrow(() ->
                        new AssertionError("candidate missing from the preview: " + publicKey));
    }

    @Test
    void anIpv6OnlyPeerIsListedButNotOfferedForImport() {
        String key = uniqueKey("V6ONLY");
        wg.setPeer(iface, key, "fd11:5ee:bad:c0de::ac5:8802/128", null);
        try {
            PeerDto.WgImportCandidate c = candidateFor(key);

            assertThat(c.alreadyExists()).as("it is not in the database").isFalse();
            assertThat(c.importable()).as("but it cannot be imported either").isFalse();
            assertThat(c.assignedIp()).isNull();
            // The IPv6 is carried through so the dialog can show the address it
            // does have, instead of a bare dash that reads as missing data.
            assertThat(c.assignedIpv6()).isEqualTo("fd11:5ee:bad:c0de::ac5:8802");
            // A /128 is a host address, not a routed network — this is an
            // ordinary client peer, and must not be mistaken for a gateway.
            assertThat(c.siteAllowedCidrs()).isNull();
        } finally {
            wg.removePeer(iface, key);
        }
    }

    @Test
    void aDualStackPeerIsStillImportable() {
        String key = uniqueKey("DUAL");
        wg.setPeer(iface, key, "10.197.136.77/32, fd11:5ee:bad:c0de::ac5:8877/128", null);
        try {
            PeerDto.WgImportCandidate c = candidateFor(key);

            assertThat(c.importable()).isTrue();
            assertThat(c.assignedIp()).isEqualTo("10.197.136.77");
            assertThat(c.assignedIpv6()).isEqualTo("fd11:5ee:bad:c0de::ac5:8877");
        } finally {
            wg.removePeer(iface, key);
        }
    }

    /** A peer with nothing usable at all is listed, and equally not offered. */
    @Test
    void aPeerWithNoAddressAtAllIsNotOffered() {
        String key = uniqueKey("NOADDR");
        wg.setPeer(iface, key, "", null);
        try {
            PeerDto.WgImportCandidate c = candidateFor(key);
            assertThat(c.alreadyExists()).isFalse();
            assertThat(c.importable()).isFalse();
        } finally {
            wg.removePeer(iface, key);
        }
    }
}
