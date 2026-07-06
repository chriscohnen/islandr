package de.chriscohnen.islandr.firewall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.proxy.FakeProxyServer;
import de.chriscohnen.islandr.proxy.ProxyClient;
import de.chriscohnen.islandr.proxy.ProxyUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SocketNftablesAdapter} (design §3, §4). The adapter
 * stages the ruleset text to the shared file, then triggers a validate/reload
 * on the host via {@link ProxyClient}. The ruleset path is a server constant, so
 * it is never sent in the request.
 */
class SocketNftablesAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RULESET = "flush table inet islandr\ntable inet islandr { }";

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

    @Test
    void validate_sendsNftValidateOp_andStagesRuleset(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        Path ruleset = dir.resolve("ruleset.nft");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);
        SocketNftablesAdapter adapter = new SocketNftablesAdapter(clientFor(socket), ruleset);

        NftablesAdapter.ValidationResult result = adapter.validate(RULESET);

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(ruleset)).isEqualTo(RULESET);
        JsonNode req = MAPPER.readTree(server.lastRequest());
        assertThat(req.get("op").asText()).isEqualTo("nft_validate");
        assertThat(req.has("ruleset")).isFalse(); // path is a server constant
    }

    @Test
    void validate_returnsFailWithStderr_whenProxyRejects(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":false,\"error\":\"syntax error at line 2\"}", socket);
        SocketNftablesAdapter adapter = new SocketNftablesAdapter(clientFor(socket), dir.resolve("ruleset.nft"));

        NftablesAdapter.ValidationResult result = adapter.validate(RULESET);

        assertThat(result.ok()).isFalse();
        assertThat(result.stderr()).contains("syntax error at line 2");
    }

    /** ProxyUnavailable must propagate from validate (not be swallowed into a fail) so the call-site degrades. */
    @Test
    void validate_throwsProxyUnavailable_whenProxyDown(@TempDir Path dir) {
        SocketNftablesAdapter adapter = new SocketNftablesAdapter(clientFor(dir.resolve("absent.sock")), dir.resolve("ruleset.nft"));

        assertThatThrownBy(() -> adapter.validate(RULESET))
                .isInstanceOf(ProxyUnavailableException.class);
    }

    @Test
    void apply_sendsNftReloadOp_andStagesRuleset(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        Path ruleset = dir.resolve("ruleset.nft");
        server = FakeProxyServer.replyingWith("{\"ok\":true}", socket);
        SocketNftablesAdapter adapter = new SocketNftablesAdapter(clientFor(socket), ruleset);

        adapter.apply(RULESET);

        assertThat(Files.readString(ruleset)).isEqualTo(RULESET);
        JsonNode req = MAPPER.readTree(server.lastRequest());
        assertThat(req.get("op").asText()).isEqualTo("nft_reload");
    }

    @Test
    void apply_throwsNftablesException_whenProxyRejects(@TempDir Path dir) throws IOException {
        Path socket = dir.resolve("p.sock");
        server = FakeProxyServer.replyingWith("{\"ok\":false,\"error\":\"nft -f failed\"}", socket);
        SocketNftablesAdapter adapter = new SocketNftablesAdapter(clientFor(socket), dir.resolve("ruleset.nft"));

        assertThatThrownBy(() -> adapter.apply(RULESET))
                .isInstanceOf(NftablesException.class)
                .hasMessageContaining("nft -f failed");
    }

    @Test
    void apply_throwsProxyUnavailable_whenProxyDown(@TempDir Path dir) {
        SocketNftablesAdapter adapter = new SocketNftablesAdapter(clientFor(dir.resolve("absent.sock")), dir.resolve("ruleset.nft"));

        assertThatThrownBy(() -> adapter.apply(RULESET))
                .isInstanceOf(ProxyUnavailableException.class);
    }
}
