package de.chriscohnen.islandr.wg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockWgAdapterTest {

    private MockWgAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new MockWgAdapter();
    }

    @Test
    void genKeypair_returnsBase64EncodedPair() {
        WgAdapter.Keypair kp = adapter.genKeypair();

        assertThat(kp.privateKey()).isNotBlank();
        assertThat(kp.publicKey()).isNotBlank();
        assertThat(kp.privateKey()).isNotEqualTo(kp.publicKey());

        byte[] priv = Base64.getDecoder().decode(kp.privateKey());
        byte[] pub = Base64.getDecoder().decode(kp.publicKey());
        assertThat(priv).hasSize(32);
        assertThat(pub).hasSize(32);
    }

    @Test
    void genKeypair_isNonDeterministic() {
        WgAdapter.Keypair a = adapter.genKeypair();
        WgAdapter.Keypair b = adapter.genKeypair();
        assertThat(a.privateKey()).isNotEqualTo(b.privateKey());
    }

    @Test
    void setPeer_thenShow_includesIt() {
        WgAdapter.Keypair kp = adapter.genKeypair();
        adapter.setPeer("wg0", kp.publicKey(), "10.8.0.5/32");

        List<WgAdapter.PeerStatus> peers = adapter.showPeers("wg0");
        assertThat(peers).hasSize(1);
        assertThat(peers.get(0).publicKey()).isEqualTo(kp.publicKey());
        assertThat(peers.get(0).allowedIps()).isEqualTo("10.8.0.5/32");
    }

    @Test
    void removePeer_dropsIt() {
        WgAdapter.Keypair a = adapter.genKeypair();
        WgAdapter.Keypair b = adapter.genKeypair();
        adapter.setPeer("wg0", a.publicKey(), "10.8.0.5/32");
        adapter.setPeer("wg0", b.publicKey(), "10.8.0.6/32");

        adapter.removePeer("wg0", a.publicKey());

        List<WgAdapter.PeerStatus> peers = adapter.showPeers("wg0");
        assertThat(peers).hasSize(1);
        assertThat(peers.get(0).publicKey()).isEqualTo(b.publicKey());
    }

    @Test
    void setPeer_isIdempotent() {
        WgAdapter.Keypair kp = adapter.genKeypair();
        adapter.setPeer("wg0", kp.publicKey(), "10.8.0.5/32");
        adapter.setPeer("wg0", kp.publicKey(), "10.8.0.5/32");

        assertThat(adapter.showPeers("wg0")).hasSize(1);
    }
}
