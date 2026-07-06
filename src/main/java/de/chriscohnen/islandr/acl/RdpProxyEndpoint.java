package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.websockets.next.OnBinaryMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint that implements the RDCleanPath proxy protocol used by
 * the IronRDP WASM browser client.
 *
 * The endpoint is at {@code ws[s]://<host>/api/v1/rdp/proxy/<portId>}.
 * Auth is validated from the session cookie on every open (JAX-RS filters
 * do not run on WebSocket upgrade).
 *
 * Message flow per connection:
 *   1. {@link #onOpen}: validate session + ACL grant
 *   2. First binary message from browser: RDCleanPath Request (ASN.1 DER)
 *      → TCP connect to RDP server → X.224 exchange → TLS upgrade
 *      → send RDCleanPath Response (ASN.1 DER) back to browser
 *   3. Subsequent binary messages: raw RDP data, relayed to TLS socket
 *   4. Reader virtual thread relays TLS socket → WebSocket (other direction)
 */
@WebSocket(path = "/api/v1/rdp/proxy/{portId}")
public class RdpProxyEndpoint {

    private static final Logger LOG = Logger.getLogger(RdpProxyEndpoint.class);

    @Inject SessionService sessions;
    @Inject RdpGrantService grants;
    @Inject SettingsService settingsService;

    private final ConcurrentHashMap<String, ConnectionState> connections = new ConcurrentHashMap<>();

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @OnOpen
    @Blocking
    public void onOpen(WebSocketConnection conn, @PathParam("portId") String portId) {
        if (!settingsService.get().ironRdpEnabled) { reject(conn, "browser-RDP is disabled in Settings (ironRdpEnabled=false)"); return; }

        String token = extractSessionCookie(conn);
        Session session = token != null ? sessions.findActive(token) : null;
        if (session == null) { reject(conn, "no valid session cookie on the WebSocket upgrade"); return; }

        // Whose access applies. Normally the session's own user. When an admin
        // previews another user (?as=<userId>, same param the my-resources view
        // uses), resolve against the impersonated user's real grants — no local-admin
        // bypass — so the preview reflects exactly what that user can reach.
        String asUserId = queryParam(conn, "as");
        String effectiveUserId;
        boolean bypassAcl;
        if (asUserId != null && !asUserId.isBlank()) {
            if (!isRequesterAdmin(session)) { reject(conn, "impersonation (?as) requires an admin session"); return; }
            effectiveUserId = asUserId;
            bypassAcl = false;
        } else {
            effectiveUserId = session.userId;
            bypassAcl = session.isLocalAdmin();
        }

        RdpGrantService.RdpTarget target = grants.resolveTarget(portId, effectiveUserId, bypassAcl);
        if (target == null) { reject(conn, "no RDP access for user=" + effectiveUserId + " on portId=" + portId + " (port missing, not RDP, or no grant)"); return; }

        LOG.infof("RDP proxy open: requester=%s effectiveUser=%s target=%s:%d",
                session.userId, effectiveUserId, target.host(), target.port());
        connections.put(conn.id(), new ConnectionState(target));
    }

    // Quarkus WebSocket Next serialises message handlers per connection,
    // so onMessage is never called concurrently for the same conn.id().
    @OnBinaryMessage
    @Blocking
    public void onMessage(WebSocketConnection conn, byte[] data) {
        ConnectionState state = connections.get(conn.id());
        if (state == null) return;

        if (!state.handshakeDone) {
            performHandshake(conn, state, data);
        } else {
            // Relay: browser → RDP server over TLS
            try {
                state.tlsSocket.getOutputStream().write(data);
            } catch (IOException e) {
                teardown(conn);
            }
        }
    }

    @OnClose
    public void onClose(WebSocketConnection conn) { teardown(conn); }

    @OnError
    public void onError(WebSocketConnection conn, Throwable err) { teardown(conn); }

    // ── RDCleanPath handshake ────────────────────────────────────────────────

    private void performHandshake(WebSocketConnection conn, ConnectionState state, byte[] requestData) {
        Socket tcp = null;
        SSLSocket tls = null;
        try {
            RdpCleanPath.Request req = RdpCleanPath.parseRequest(requestData);

            // Connect to the target from our DB — ignore req.destination() for security
            tcp = new Socket();
            tcp.connect(new InetSocketAddress(state.target.host(), state.target.port()), 5_000);

            // X.224 Connection Request → RDP server
            tcp.getOutputStream().write(req.x224ConnectionRequest());
            tcp.getOutputStream().flush();

            // X.224 Connection Confirm ← RDP server
            byte[] x224Response = readX224(tcp.getInputStream());

            // Upgrade to TLS (RDP servers use self-signed certs)
            tls = upgradeTls(tcp, state.target.host(), state.target.port());
            tls.startHandshake();

            // Extract cert chain for the RDCleanPath response
            Certificate[] peerCerts = tls.getSession().getPeerCertificates();
            byte[][] certChain = new byte[peerCerts.length][];
            for (int i = 0; i < peerCerts.length; i++) certChain[i] = peerCerts[i].getEncoded();

            // Send RDCleanPath Response
            String serverAddr = state.target.host() + ":" + state.target.port();
            byte[] response = RdpCleanPath.buildResponse(serverAddr, x224Response, certChain);
            conn.sendBinary(Buffer.buffer(response)).await().indefinitely();

            state.tlsSocket = tls;
            state.handshakeDone = true;

            // Reader: TLS → WebSocket (runs on a virtual thread, parallel to onMessage)
            state.readerThread = Thread.ofVirtual().start(() -> relayTlsToWs(conn, state));

        } catch (Exception e) {
            // Surface the real reason: this is almost always the RDP target being
            // unreachable (wrong IP, refused, timeout) or not speaking RDP-over-TLS.
            // Without this log the failure is invisible to the operator.
            LOG.warnf(e, "RDP proxy handshake to %s:%d failed", state.target.host(), state.target.port());
            if (tls != null) { try { tls.close(); } catch (IOException ignored) {} }
            else if (tcp != null) { try { tcp.close(); } catch (IOException ignored) {} }
            sendError(conn, 502);
            teardown(conn);
            conn.close().subscribe().with(v -> {});
        }
    }

    private void relayTlsToWs(WebSocketConnection conn, ConnectionState state) {
        byte[] buf = new byte[65536];
        try {
            InputStream in = state.tlsSocket.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] chunk = Arrays.copyOf(buf, n);
                conn.sendBinary(Buffer.buffer(chunk)).await().indefinitely();
            }
        } catch (Exception ignored) {
            // Normal on disconnect
        } finally {
            teardown(conn);
            conn.close().subscribe().with(v -> {});
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void teardown(WebSocketConnection conn) {
        ConnectionState state = connections.remove(conn.id());
        if (state != null) state.close();
    }

    private void reject(WebSocketConnection conn, String reason) {
        LOG.infof("RDP proxy rejected connection: %s", reason);
        conn.close().await().indefinitely();
    }

    private void sendError(WebSocketConnection conn, int httpStatus) {
        try {
            byte[] errPdu = RdpCleanPath.buildError(1, httpStatus);
            conn.sendBinary(Buffer.buffer(errPdu)).await().indefinitely();
        } catch (Exception ignored) {}
    }

    private boolean isRequesterAdmin(Session session) {
        return session.isLocalAdmin() || grants.isAdmin(session.userId);
    }

    private static String queryParam(WebSocketConnection conn, String name) {
        String query = conn.handshakeRequest().query();
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(name)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String extractSessionCookie(WebSocketConnection conn) {
        String header = conn.handshakeRequest().header("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length == 2 && SessionFilter.COOKIE_NAME.equals(kv[0].strip())) {
                return kv[1].strip();
            }
        }
        return null;
    }

    private static byte[] readX224(InputStream in) throws IOException {
        // TPKT header: version(1) + reserved(1) + total_length(2, big-endian)
        byte[] header = in.readNBytes(4);
        if (header.length < 4) throw new IOException("Truncated X.224 TPKT header");
        int total = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] rest = in.readNBytes(total - 4);
        byte[] result = new byte[total];
        System.arraycopy(header, 0, result, 0, 4);
        System.arraycopy(rest, 0, result, 4, rest.length);
        return result;
    }

    @SuppressWarnings("all")
    private static SSLSocket upgradeTls(Socket tcp, String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(tcp, host, port, true);
        ssl.setUseClientMode(true);
        return ssl;
    }

    // ── Per-connection state ─────────────────────────────────────────────────

    private static final class ConnectionState {
        final RdpGrantService.RdpTarget target;
        volatile boolean handshakeDone = false;
        volatile SSLSocket tlsSocket;
        volatile Thread readerThread;

        ConnectionState(RdpGrantService.RdpTarget target) { this.target = target; }

        void close() {
            if (tlsSocket != null) {
                try { tlsSocket.close(); } catch (IOException ignored) {}
            }
            if (readerThread != null) readerThread.interrupt();
        }
    }
}
