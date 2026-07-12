package de.chriscohnen.islandr.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProxyClient}, driven against an in-process fake
 * Unix-domain-socket server speaking the line-delimited JSON protocol
 * (design §4, §9). No Go binary, no Docker, no host required.
 *
 * <p>Traces to UC-04 (degraded mode) and BR-029 (never fake success).
 */
class ProxyClientTest {

    private FakeProxyServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** BR-029: a real "ok" response is parsed and exposed as such. */
    @Test
    void send_parsesOkResponse_fromFakeServer(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);

        ProxyClient client = new ProxyClient(socket, Duration.ofSeconds(2));
        ProxyResponse response = client.send(Map.of("op", "wg_show"));

        assertThat(response.ok()).isTrue();
        assertThat(response.error()).isNull();
    }

    /** The request is written as a single JSON line carrying the requested fields (order-agnostic). */
    @Test
    void send_writesRequestAsSingleJsonLine(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);

        ProxyClient client = new ProxyClient(socket, Duration.ofSeconds(2));
        client.send(Map.of("op", "wg_remove_peer", "pubkey", "ABC="));

        String request = server.lastRequest();
        assertThat(request).doesNotContain("\n");
        JsonNode parsed = new ObjectMapper().readTree(request);
        assertThat(parsed.get("op").asText()).isEqualTo("wg_remove_peer");
        assertThat(parsed.get("pubkey").asText()).isEqualTo("ABC=");
    }

    /** A server-reported operation failure surfaces as ok=false + error, not an exception. */
    @Test
    void send_exposesServerError_whenOkIsFalse(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":false,\"error\":\"nft rejected ruleset\"}", socket);

        ProxyClient client = new ProxyClient(socket, Duration.ofSeconds(2));
        ProxyResponse response = client.send(Map.of("op", "nft_reload"));

        assertThat(response.ok()).isFalse();
        assertThat(response.error()).isEqualTo("nft rejected ruleset");
    }

    /** Response body fields (e.g. wg_show dump) are readable for the adapters. */
    @Test
    void send_exposesResponseBodyFields(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":true,\"dump\":\"peerline\"}", socket);

        ProxyClient client = new ProxyClient(socket, Duration.ofSeconds(2));
        ProxyResponse response = client.send(Map.of("op", "wg_show"));

        assertThat(response.body().get("dump").asText()).isEqualTo("peerline");
    }

    /** Design §3/§9: connect failure → typed ProxyUnavailableException, never a generic error. */
    @Test
    void send_throwsProxyUnavailable_whenSocketAbsent(@TempDir Path dir) {
        Path socket = dir.resolve("does-not-exist.sock");

        ProxyClient client = new ProxyClient(socket, Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.send(Map.of("op", "wg_show")))
                .isInstanceOf(ProxyUnavailableException.class);
    }

    /** Design §3: a proxy that accepts but never replies must time out as unavailable, not hang. */
    @Test
    void send_throwsProxyUnavailable_whenServerNeverReplies(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.acceptingButSilent(socket);

        ProxyClient client = new ProxyClient(socket, Duration.ofMillis(200));

        assertThatThrownBy(() -> client.send(Map.of("op", "wg_show")))
                .isInstanceOf(ProxyUnavailableException.class);
    }
}
