package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.peer.IpSubnet;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in resource-name DNS resolver (ADR-0023) — a hand-rolled UDP/TCP
 * listener bound to the hub's own tunnel IP. Authoritative for the managed
 * zone ({@link DnsQueryHandler}); everything else is forwarded verbatim to
 * the admin-configured upstream(s).
 *
 * <p>Started/stopped by {@link #reconcile()}, called once at boot and again
 * after every settings save ({@code SettingsResource}) — there is no CDI
 * event bus in this codebase yet for a cleaner "settings changed" hook, so
 * this is the simplest wiring that keeps the running state in sync with
 * {@code Settings.dnsResolverEnabled} without polling.
 *
 * <p>Binding port 53 needs {@code CAP_NET_BIND_SERVICE} under the unprivileged
 * process model (ADR-0011) — this was not part of that ADR's sudoers scope.
 * A bind failure is logged with the fix and leaves the resolver not running;
 * it never crashes the app or blocks unrelated settings saves.
 */
@ApplicationScoped
public class DnsResolverService {

    private static final Logger LOG = Logger.getLogger(DnsResolverService.class);

    private static final int UDP_BUFFER_SIZE = 512;
    private static final int UPSTREAM_BUFFER_SIZE = 4096;
    private static final int UPSTREAM_TIMEOUT_MS = 3000;
    private static final int TCP_QUERY_TIMEOUT_MS = 5000;
    private static final int ANSWER_TTL_SECONDS = 30;

    @Inject DnsQueryHandler queryHandler;

    @ConfigProperty(name = "islandr.dns.port", defaultValue = "53")
    int port;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DatagramSocket udpSocket;
    private volatile ServerSocket tcpSocket;

    void onStart(@Observes StartupEvent ev) {
        reconcile();
    }

    /** True while the UDP listener is actually bound — distinct from
     *  {@code Settings.dnsResolverEnabled}, which can be true while this is
     *  false (bind failed, e.g. missing {@code CAP_NET_BIND_SERVICE}; see
     *  {@link #start}). The System → DNS status page shows both. */
    public boolean isRunning() {
        return running.get();
    }

    @PreDestroy
    void onShutdown() {
        stop();
    }

    /** Starts or stops the listener to match {@code Settings.dnsResolverEnabled}.
     *  Idempotent — a no-op when already in the desired state. */
    public synchronized void reconcile() {
        DnsQueryHandler.ResolverConfig cfg;
        try {
            cfg = queryHandler.currentConfig();
        } catch (RuntimeException e) {
            // Settings row not seeded yet (fresh DB mid-migration) — nothing to do.
            return;
        }
        if (cfg.enabled() && !running.get()) {
            start(cfg.wgSubnet());
        } else if (!cfg.enabled() && running.get()) {
            stop();
        }
    }

    private synchronized void start(String wgSubnet) {
        if (running.get() || wgSubnet == null || wgSubnet.isBlank()) return;

        String bindIp;
        try {
            bindIp = IpSubnet.parse(wgSubnet).networkAddress();
        } catch (RuntimeException e) {
            LOG.warnf("dns resolver: could not derive the hub tunnel IP from wgSubnet '%s' — %s",
                    wgSubnet, e.getMessage());
            return;
        }
        InetSocketAddress addr = new InetSocketAddress(bindIp, port);

        DatagramSocket udp;
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.bind(addr);
        } catch (IOException e) {
            LOG.warnf("dns resolver: could not bind UDP %s:%d (%s). If %d is a privileged port, " +
                    "grant CAP_NET_BIND_SERVICE to the islandr process (systemd: " +
                    "AmbientCapabilities=CAP_NET_BIND_SERVICE) or set islandr.dns.port to an " +
                    "unprivileged port and forward it externally. The resolver stays off until " +
                    "this is fixed and the setting is saved again.", bindIp, port, e.getMessage(), port);
            return;
        }

        ServerSocket tcp = null;
        try {
            tcp = new ServerSocket();
            tcp.setReuseAddress(true);
            tcp.bind(addr);
        } catch (IOException e) {
            LOG.warnf("dns resolver: could not bind TCP %s:%d (%s) — continuing UDP-only. " +
                    "This resolver's own answers are always small, so TCP fallback (RFC 1035's " +
                    "truncated-response path) is a completeness nicety here, not a functional gap.",
                    bindIp, port, e.getMessage());
        }

        udpSocket = udp;
        tcpSocket = tcp;
        running.set(true);
        Thread.ofVirtual().name("dns-resolver-udp").start(this::udpLoop);
        if (tcp != null) {
            Thread.ofVirtual().name("dns-resolver-tcp").start(this::tcpLoop);
        }
        LOG.infof("dns resolver: listening on %s:%d (udp%s)",
                bindIp, port, tcp != null ? "+tcp" : " only — tcp bind failed, see above");
    }

    private synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (udpSocket != null) udpSocket.close();
        if (tcpSocket != null) {
            try {
                tcpSocket.close();
            } catch (IOException ignored) {
                // closing an already-broken socket — nothing to act on
            }
        }
        udpSocket = null;
        tcpSocket = null;
        LOG.info("dns resolver: stopped");
    }

    private void udpLoop() {
        DatagramSocket socket = udpSocket;
        byte[] buf = new byte[UDP_BUFFER_SIZE];
        while (running.get() && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running.get()) LOG.debugf("dns resolver: udp receive failed — %s", e.getMessage());
                continue;
            }
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            InetAddress clientAddr = packet.getAddress();
            int clientPort = packet.getPort();
            Thread.ofVirtual().start(() -> {
                byte[] response = handle(data, clientAddr.getHostAddress());
                if (response == null) return;
                try {
                    socket.send(new DatagramPacket(response, response.length, clientAddr, clientPort));
                } catch (IOException e) {
                    LOG.debugf("dns resolver: udp send failed — %s", e.getMessage());
                }
            });
        }
    }

    private void tcpLoop() {
        ServerSocket server = tcpSocket;
        while (running.get() && !server.isClosed()) {
            Socket conn;
            try {
                conn = server.accept();
            } catch (IOException e) {
                if (running.get()) LOG.debugf("dns resolver: tcp accept failed — %s", e.getMessage());
                continue;
            }
            Thread.ofVirtual().start(() -> handleTcp(conn));
        }
    }

    // RFC 1035 §4.2.2 — TCP messages are prefixed with a 2-byte length. One
    // query per connection, matching how a stub resolver actually uses the
    // TCP fallback path (retry-over-TCP after a truncated UDP answer).
    private void handleTcp(Socket conn) {
        try (conn) {
            conn.setSoTimeout(TCP_QUERY_TIMEOUT_MS);
            var in = conn.getInputStream();
            var out = conn.getOutputStream();
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) return;
            int len = (hi << 8) | lo;
            byte[] data = in.readNBytes(len);
            if (data.length != len) return;
            String sourceIp = ((InetSocketAddress) conn.getRemoteSocketAddress()).getAddress().getHostAddress();
            byte[] response = handle(data, sourceIp);
            if (response == null) return;
            out.write((response.length >>> 8) & 0xff);
            out.write(response.length & 0xff);
            out.write(response);
        } catch (IOException e) {
            LOG.debugf("dns resolver: tcp connection failed — %s", e.getMessage());
        }
    }

    /** Parses, resolves (authoritative or forward), returns the bytes to send
     *  back — or {@code null} if nothing should be sent (malformed request,
     *  or a forward that got no upstream reply in time). */
    private byte[] handle(byte[] query, String sourceIp) {
        DnsWireFormat.Query parsed;
        try {
            parsed = DnsWireFormat.parseQuery(query, query.length);
        } catch (IllegalArgumentException e) {
            return null; // not a shape this narrow parser understands — drop, don't guess
        }

        DnsQueryHandler.Resolution resolution;
        try {
            resolution = queryHandler.resolve(parsed.name(), sourceIp);
        } catch (RuntimeException e) {
            LOG.warnf("dns resolver: query handling failed for '%s' — %s", parsed.name(), e.getMessage());
            return null;
        }

        if (resolution instanceof DnsQueryHandler.Resolution.Answer answer) {
            byte[] addressBytes = literalAddressBytes(answer.ip());
            if (addressBytes == null) return DnsWireFormat.buildError(query, parsed, DnsWireFormat.RCODE_NXDOMAIN);
            return DnsWireFormat.buildAnswer(query, parsed, addressBytes, ANSWER_TTL_SECONDS);
        }
        if (resolution instanceof DnsQueryHandler.Resolution.NxDomain) {
            return DnsWireFormat.buildError(query, parsed, DnsWireFormat.RCODE_NXDOMAIN);
        }
        return forward(query);
    }

    private static byte[] literalAddressBytes(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Forwards the raw query to the configured upstream(s) in order, returns
     *  the first reply byte-for-byte — never parsed, see {@link DnsWireFormat}'s
     *  class doc. {@code null} if every upstream times out or none are
     *  configured; the querying client's own resolver then retries or gives
     *  up on its own, same as talking to any unreachable resolver directly. */
    private byte[] forward(byte[] query) {
        List<String> upstreams = queryHandler.currentConfig().upstreams();
        for (String upstream : upstreams) {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(UPSTREAM_TIMEOUT_MS);
                sock.send(new DatagramPacket(query, query.length, InetAddress.getByName(upstream), 53));
                byte[] buf = new byte[UPSTREAM_BUFFER_SIZE];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                sock.receive(reply);
                return Arrays.copyOf(reply.getData(), reply.getLength());
            } catch (IOException e) {
                LOG.debugf("dns resolver: upstream %s failed — %s", upstream, e.getMessage());
            }
        }
        return null;
    }

    /** {@code upstream} is which configured server actually answered,
     *  {@code ip} the resolved address. */
    public record UpstreamAnswer(String upstream, String ip) {}

    /** Live query against the configured upstream(s) for a name outside the
     *  managed zone — used only by the System → DNS page's admin lookup
     *  preview, so "not managed" can show an actual answer and which server
     *  provided it, not just "would be forwarded". Synthesizes its own query
     *  ({@link DnsWireFormat#buildQuery}) and parses the response
     *  ({@link DnsWireFormat#parseFirstAnswerAddress}) — the one place in this
     *  class that does either; the real listening path ({@link #forward})
     *  only ever relays a real peer's own query bytes, untouched both ways.
     *  {@code null} if no upstream answers with a usable A/AAAA record. */
    public UpstreamAnswer queryUpstreamForPreview(String name) {
        byte[] query = DnsWireFormat.buildQuery(1, name, DnsWireFormat.TYPE_A);
        for (String upstream : queryHandler.currentConfig().upstreams()) {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(UPSTREAM_TIMEOUT_MS);
                sock.send(new DatagramPacket(query, query.length, InetAddress.getByName(upstream), 53));
                byte[] buf = new byte[UPSTREAM_BUFFER_SIZE];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                sock.receive(reply);
                byte[] data = Arrays.copyOf(reply.getData(), reply.getLength());
                String ip = DnsWireFormat.parseFirstAnswerAddress(data);
                if (ip != null) return new UpstreamAnswer(upstream, ip);
            } catch (IOException e) {
                LOG.debugf("dns resolver: preview query to upstream %s failed — %s", upstream, e.getMessage());
            }
        }
        return null;
    }
}
