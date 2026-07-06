package de.chriscohnen.islandr.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Sends one line-delimited JSON request to the host-side {@code islandr-proxy}
 * over a Unix domain socket and parses the single-line response (design §4).
 *
 * <p>Pure JDK transport ({@link java.net.UnixDomainSocketAddress}, Java 16+) —
 * no native library, no third-party socket dependency (D4). JSON uses the
 * Jackson mapper the app already ships with.
 *
 * <p>One request per connection: connect, write the request line, read the
 * response line, close. Any unreachability — missing socket, refused connection,
 * I/O error, or a response not arriving within the timeout — is raised as
 * {@link ProxyUnavailableException} so call-sites can degrade honestly instead
 * of failing or faking success.
 */
public class ProxyClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path socketPath;
    private final Duration timeout;

    public ProxyClient(Path socketPath, Duration timeout) {
        this.socketPath = socketPath;
        this.timeout = timeout;
    }

    /**
     * Send {@code request} as a JSON line and return the parsed response.
     *
     * @throws ProxyUnavailableException if the proxy cannot be reached or does
     *         not answer within the configured timeout.
     */
    public ProxyResponse send(Map<String, Object> request) {
        byte[] line;
        try {
            line = (MAPPER.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Serializing a plain map should never fail; treat as a programming error.
            throw new IllegalArgumentException("Cannot serialize proxy request", e);
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try (Selector selector = Selector.open();
             SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {

            channel.configureBlocking(false);
            if (!channel.connect(address)) {
                channel.register(selector, SelectionKey.OP_CONNECT);
                awaitReady(selector, deadline);
                if (!channel.finishConnect()) {
                    throw new ProxyUnavailableException("Proxy connect did not complete: " + socketPath);
                }
            }

            writeFully(channel, selector, ByteBuffer.wrap(line), deadline);
            String response = readLine(channel, selector, deadline);
            return parse(response);

        } catch (ProxyUnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new ProxyUnavailableException("Proxy socket I/O failed: " + socketPath, e);
        }
    }

    private void writeFully(SocketChannel channel, Selector selector, ByteBuffer buf, long deadline)
            throws IOException {
        channel.register(selector, SelectionKey.OP_WRITE);
        while (buf.hasRemaining()) {
            awaitReady(selector, deadline);
            channel.write(buf);
        }
    }

    private String readLine(SocketChannel channel, Selector selector, long deadline) throws IOException {
        channel.register(selector, SelectionKey.OP_READ);
        StringBuilder line = new StringBuilder();
        ByteBuffer buf = ByteBuffer.allocate(1024);
        while (true) {
            awaitReady(selector, deadline);
            int n = channel.read(buf);
            if (n == -1) {
                throw new ProxyUnavailableException("Proxy closed before sending a response line");
            }
            buf.flip();
            while (buf.hasRemaining()) {
                char c = (char) buf.get();
                if (c == '\n') {
                    return line.toString();
                }
                line.append(c);
            }
            buf.clear();
        }
    }

    /** Block until the channel is ready or the deadline passes; timeout → unavailable. */
    private void awaitReady(Selector selector, long deadline) throws IOException {
        long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
        if (remainingMillis <= 0) {
            throw new ProxyUnavailableException("Proxy timed out: " + socketPath);
        }
        int ready = selector.select(remainingMillis);
        selector.selectedKeys().clear();
        if (ready == 0) {
            throw new ProxyUnavailableException("Proxy timed out: " + socketPath);
        }
    }

    private ProxyResponse parse(String responseLine) {
        try {
            JsonNode body = MAPPER.readTree(responseLine);
            boolean ok = body.path("ok").asBoolean(false);
            JsonNode errorNode = body.get("error");
            String error = (errorNode != null && !errorNode.isNull()) ? errorNode.asText() : null;
            return new ProxyResponse(ok, error, body);
        } catch (IOException e) {
            throw new ProxyUnavailableException("Proxy sent a malformed response line: " + responseLine, e);
        }
    }
}
