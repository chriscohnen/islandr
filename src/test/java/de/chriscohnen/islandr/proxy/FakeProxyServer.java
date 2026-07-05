package de.chriscohnen.islandr.proxy;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * In-process fake for the host-side {@code islandr-proxy}, used by the socket-mode
 * unit tests (design §9): no Go binary, no Docker, no host required.
 *
 * <p>Serves the line-delimited JSON protocol (design §4). Accepts connections in
 * a loop until {@link #close()} — one request line per connection, matching the
 * client's "one request per connection" contract. Each request line is recorded
 * in {@link #requests()}; the response is produced by the supplied handler, which
 * may return {@code null} to stay silent (exercises the client timeout).
 */
public final class FakeProxyServer implements AutoCloseable {

    private final ServerSocketChannel channel;
    private final Thread thread;
    private final Function<String, String> handler;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    private FakeProxyServer(ServerSocketChannel channel, Function<String, String> handler) {
        this.channel = channel;
        this.handler = handler;
        this.thread = new Thread(this::acceptLoop, "fake-proxy");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Reply to every request with the same canned line. */
    public static FakeProxyServer replyingWith(String reply, Path socket) throws IOException {
        return new FakeProxyServer(bind(socket), request -> reply);
    }

    /** Reply per request via the handler (return {@code null} to stay silent). */
    public static FakeProxyServer handling(Function<String, String> handler, Path socket) throws IOException {
        return new FakeProxyServer(bind(socket), handler);
    }

    /** Accept the connection but never respond — exercises the client read timeout. */
    public static FakeProxyServer acceptingButSilent(Path socket) throws IOException {
        return new FakeProxyServer(bind(socket), request -> null);
    }

    private static ServerSocketChannel bind(Path socket) throws IOException {
        ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ch.bind(UnixDomainSocketAddress.of(socket));
        return ch;
    }

    private void acceptLoop() {
        while (running) {
            try (SocketChannel conn = channel.accept()) {
                String request = readLine(conn);
                requests.add(request);
                String reply = handler.apply(request);
                if (reply == null) {
                    Thread.sleep(5_000); // silent: hold the connection so the client blocks
                } else {
                    conn.write(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.UTF_8)));
                }
            } catch (IOException | InterruptedException e) {
                return; // channel closed on teardown, or interrupted — stop serving
            }
        }
    }

    private static String readLine(SocketChannel conn) throws IOException {
        StringBuilder line = new StringBuilder();
        ByteBuffer buf = ByteBuffer.allocate(1024);
        while (conn.read(buf) != -1) {
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
        return line.toString();
    }

    /** The last request line the server received. */
    public String lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    /** All request lines received, in order. */
    public List<String> requests() {
        return requests;
    }

    @Override
    public void close() throws IOException {
        running = false;
        channel.close();
    }
}
