package de.chriscohnen.islandr.wg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.proxy.FakeProxyServer;
import de.chriscohnen.islandr.proxy.ProxyClient;
import de.chriscohnen.islandr.proxy.ProxyUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SocketWgAdapter} (design §3, §4). Two concerns:
 * <ul>
 *   <li><b>Userspace crypto</b> (genKeypair/derivePublicKey/genPsk) runs in-JVM via
 *       JDK X25519 — no proxy, no {@code wg} binary. Verified against the RFC 7748
 *       §6.1 test vector so keys interoperate with real WireGuard.
 *   <li><b>Privileged ops</b> (setPeer/removePeer/showPeers/probeServer) map to JSON
 *       ops routed through {@link ProxyClient} to a fake proxy socket.
 * </ul>
 */
class SocketWgAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // RFC 7748 §6.1 — the byte order X25519 uses directly is the WireGuard key encoding.
    private static final byte[] RFC7748_PRIVATE = hex(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    private static final byte[] RFC7748_PUBLIC = hex(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");

    private FakeProxyServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private static ProxyClient clientFor(Path socket) {
        return new ProxyClient(socket, Duration.ofSeconds(2));
    }

    // ── userspace crypto (no proxy) ──────────────────────────────────────────

    /** Interop safety: our public-key derivation matches the RFC 7748 vector byte-for-byte. */
    @Test
    void derivePublicKey_matchesRfc7748Vector(@TempDir Path dir) {
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(dir.resolve("unused.sock")));

        String publicKey = adapter.derivePublicKey(Base64.getEncoder().encodeToString(RFC7748_PRIVATE));

        assertThat(publicKey).isEqualTo(Base64.getEncoder().encodeToString(RFC7748_PUBLIC));
    }

    /** A generated keypair is two distinct 32-byte base64 values whose public derives from the private. */
    @Test
    void genKeypair_producesPairingCurve25519Keys(@TempDir Path dir) {
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(dir.resolve("unused.sock")));

        WgAdapter.Keypair kp = adapter.genKeypair();

        assertThat(Base64.getDecoder().decode(kp.privateKey())).hasSize(32);
        assertThat(Base64.getDecoder().decode(kp.publicKey())).hasSize(32);
        assertThat(kp.publicKey()).isNotEqualTo(kp.privateKey());
        assertThat(adapter.derivePublicKey(kp.privateKey())).isEqualTo(kp.publicKey());
    }

    @Test
    void genPsk_is32RandomBase64Bytes(@TempDir Path dir) {
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(dir.resolve("unused.sock")));

        String psk = adapter.genPsk();

        assertThat(Base64.getDecoder().decode(psk)).hasSize(32);
        assertThat(adapter.genPsk()).isNotEqualTo(psk); // non-deterministic
    }

    // ── privileged ops (through the proxy) ───────────────────────────────────

    @Test
    void setPeer_sendsWgSetPeerOp(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(socket));

        adapter.setPeer("wg0", "PUBKEY=", "10.8.0.5/32", null);

        JsonNode req = MAPPER.readTree(server.lastRequest());
        assertThat(req.get("op").asText()).isEqualTo("wg_set_peer");
        assertThat(req.get("pubkey").asText()).isEqualTo("PUBKEY=");
        assertThat(req.get("allowedIps").asText()).isEqualTo("10.8.0.5/32");
    }

    /** A reachable proxy that reports failure surfaces as WgException, not ProxyUnavailable. */
    @Test
    void setPeer_throwsWgException_whenProxyReportsFailure(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":false,\"error\":\"invalid key\"}", socket);
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(socket));

        assertThatThrownBy(() -> adapter.setPeer("wg0", "BAD", "10.8.0.5/32", null))
                .isInstanceOf(WgException.class)
                .hasMessageContaining("invalid key");
    }

    /** BR-027: proxy down → ProxyUnavailableException propagates (call-site will degrade). */
    @Test
    void setPeer_throwsProxyUnavailable_whenProxyDown(@TempDir Path dir) {
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(dir.resolve("absent.sock")));

        assertThatThrownBy(() -> adapter.setPeer("wg0", "PUBKEY=", "10.8.0.5/32", null))
                .isInstanceOf(ProxyUnavailableException.class);
    }

    @Test
    void removePeer_sendsWgRemovePeerOp(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(socket));

        adapter.removePeer("wg0", "PUBKEY=");

        JsonNode req = MAPPER.readTree(server.lastRequest());
        assertThat(req.get("op").asText()).isEqualTo("wg_remove_peer");
        assertThat(req.get("pubkey").asText()).isEqualTo("PUBKEY=");
    }

    @Test
    void showPeers_parsesDumpFromResponse(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        // Real tabs + newline; Jackson escapes them correctly when building the reply JSON.
        String dump = "SRVPRIV\tSRVPUB\t51820\toff\n"
                + "PEERPUB\t(none)\t203.0.113.5:51820\t10.8.0.5/32\t1700000000\t1000\t2000\toff";
        String reply = MAPPER.writeValueAsString(java.util.Map.of("ok", true, "dump", dump));
        server = FakeProxyServer.replyingWith(reply, socket);
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(socket));

        List<WgAdapter.PeerStatus> peers = adapter.showPeers("wg0");

        assertThat(peers).hasSize(1);
        assertThat(peers.get(0).publicKey()).isEqualTo("PEERPUB");
        assertThat(peers.get(0).allowedIps()).isEqualTo("10.8.0.5/32");
    }

    /** Contract: probeServer returns null when the interface/proxy is unreachable. */
    @Test
    void probeServer_returnsNull_whenProxyDown(@TempDir Path dir) {
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(dir.resolve("absent.sock")));

        assertThat(adapter.probeServer("wg0")).isNull();
    }

    /** #37: probeServerDetailed must surface the real reason, not just fail silently. */
    @Test
    void probeServerDetailed_carriesRealError_whenProxyUnreachable(@TempDir Path dir) {
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(dir.resolve("absent.sock")));

        WgAdapter.ProbeResult result = adapter.probeServerDetailed("wg0");

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    /** #37: a reachable proxy reporting ok:false must surface its error text too. */
    @Test
    void probeServerDetailed_carriesRealError_whenWgShowFails(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        String reply = MAPPER.writeValueAsString(java.util.Map.of(
                "ok", false, "error", "wg_show failed: exit status 1: Unable to access interface: No such device"));
        server = FakeProxyServer.replyingWith(reply, socket);
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(socket));

        WgAdapter.ProbeResult result = adapter.probeServerDetailed("wg0");

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("No such device");
    }

    /** setIfMtu is a no-op in socket mode (design §4): no exception, no proxy call. */
    @Test
    void setIfMtu_isNoOp(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);
        SocketWgAdapter adapter = new SocketWgAdapter(clientFor(socket));

        adapter.setIfMtu("wg0", 1420);

        assertThat(server.requests()).isEmpty();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
