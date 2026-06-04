package de.chriscohnen.islandr.wg;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code wg show <iface> dump} parser without invoking wg.
 * Fixtures are real-world-shaped output captured from the wg man page and from
 * an Ubuntu hub running wireguard-tools v1.0.20210914.
 */
class ParseShowDumpTest {

    @Test
    void parses_emptyInterface_returnsNoPeers() {
        String dump = "PRIVATE_KEY\tPUBLIC_KEY\t51820\toff\n";
        assertThat(RealWgAdapter.parseShowDump(dump)).isEmpty();
    }

    @Test
    void parses_singlePeer_withHandshake() {
        // interface line + one peer line. fields are TAB-separated.
        String dump = String.join("\n",
                "iPriv\tiPub\t51820\toff",
                "pBOBkey=\t(none)\t203.0.113.5:51820\t10.8.0.5/32\t1719000000\t1234567\t7654321\t25");

        List<WgAdapter.PeerStatus> peers = RealWgAdapter.parseShowDump(dump);

        assertThat(peers).hasSize(1);
        WgAdapter.PeerStatus p = peers.get(0);
        assertThat(p.publicKey()).isEqualTo("pBOBkey=");
        assertThat(p.endpoint()).isEqualTo("203.0.113.5:51820");
        assertThat(p.allowedIps()).isEqualTo("10.8.0.5/32");
        assertThat(p.lastHandshake()).isEqualTo(Instant.ofEpochSecond(1719000000));
        assertThat(p.rxBytes()).isEqualTo(1234567);
        assertThat(p.txBytes()).isEqualTo(7654321);
    }

    @Test
    void parses_peerWithoutHandshake_yieldsNullEndpointAndHandshake() {
        String dump = String.join("\n",
                "iPriv\tiPub\t51820\toff",
                "pNEWkey=\t(none)\t(none)\t10.8.0.6/32\t0\t0\t0\t0");

        WgAdapter.PeerStatus p = RealWgAdapter.parseShowDump(dump).get(0);

        assertThat(p.endpoint()).isNull();
        assertThat(p.lastHandshake()).isNull();
        assertThat(p.rxBytes()).isZero();
        assertThat(p.txBytes()).isZero();
    }

    @Test
    void parses_multiplePeers() {
        String dump = String.join("\n",
                "iPriv\tiPub\t51820\toff",
                "pA=\t(none)\t203.0.113.5:51820\t10.8.0.5/32\t1719000000\t100\t200\t25",
                "pB=\t(none)\t(none)\t10.8.0.6/32\t0\t0\t0\t0",
                "pC=\t(none)\t198.51.100.7:51820\t10.8.0.7/32,10.8.0.8/32\t1719003600\t500\t800\t25");

        List<WgAdapter.PeerStatus> peers = RealWgAdapter.parseShowDump(dump);

        assertThat(peers).hasSize(3);
        assertThat(peers).extracting(WgAdapter.PeerStatus::publicKey)
                .containsExactly("pA=", "pB=", "pC=");
        assertThat(peers.get(2).allowedIps()).isEqualTo("10.8.0.7/32,10.8.0.8/32");
    }

    @Test
    void ignores_trailingBlankLines() {
        String dump = String.join("\n",
                "iPriv\tiPub\t51820\toff",
                "pA=\t(none)\t203.0.113.5:51820\t10.8.0.5/32\t1719000000\t1\t1\t25",
                "",
                "");

        assertThat(RealWgAdapter.parseShowDump(dump)).hasSize(1);
    }
}
