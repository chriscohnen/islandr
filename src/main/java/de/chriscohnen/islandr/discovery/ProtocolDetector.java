package de.chriscohnen.islandr.discovery;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes what actually speaks on one already-open TCP port — the port number
 * alone does not say: device web UIs sit on 8006, 8123, 9443, 10000 just as
 * often as on 80/443, and whether TLS is in front is not visible from the
 * number either.
 *
 * <p><b>Two classes of protocol, tried in this order:</b>
 * <ol>
 *   <li><b>Banner-first</b> — the far end speaks before being asked:
 *       {@link #SSH_PREFIX SSH} and {@link #RFB_PREFIX VNC/RFB}. A single
 *       {@code read()} after connecting either produces one of these two
 *       prefixes immediately, or nothing — real banner services do not make a
 *       caller wait.</li>
 *   <li><b>Ask-first</b> — a small probe, then the answer decides: HTTPS (a
 *       real TLS handshake), HTTP (a plain {@code GET}), RTSP
 *       ({@code OPTIONS}), RDP (an X.224 connection request). Each of these
 *       needs its own fresh connection, since the banner attempt already
 *       consumed the first one without getting a reply.</li>
 * </ol>
 *
 * <p><b>Deliberately not a raw-socket sniffer.</b> Every probe here is a real,
 * unprivileged client connection — the exact posture as {@link PortScanner}
 * and {@link de.chriscohnen.islandr.dns.SsdpLookup} (ADR-0011/0014): no
 * {@code CAP_NET_RAW}, nothing that needs root beyond what already runs.
 *
 * <p><b>The HTTPS trust manager accepts anything.</b> That is correct only
 * because this class never uses the connection for anything but detection: no
 * credentials, no data submitted, nothing kept beyond the certificate's own
 * public fields (CN, expiry) used to help *name* a device. This trust
 * posture must never leak into the RDP browser proxy or the portal — see
 * ADR-0006 on what a resource port protects and does not.
 *
 * <p>No redirects are followed and every read is capped, the same fence
 * {@link de.chriscohnen.islandr.dns.SsdpLookup} puts around its own HTTP GET —
 * a probe against an unauthenticated device must not become an open-ended
 * fetch of whatever it points at.
 */
public final class ProtocolDetector {

    private static final String SSH_PREFIX = "SSH-";
    private static final String RFB_PREFIX = "RFB ";
    private static final int MAX_BANNER_BYTES = 256;
    private static final int MAX_BODY_BYTES = 8 * 1024;

    /** Real banner services answer within a handful of milliseconds of the
     *  TCP handshake completing — they do not wait to be asked. Anything that
     *  still has not sent a byte after this window is not one, and the rest
     *  of the given {@code timeout} budget belongs to the ask-first probes
     *  instead of a doomed wait for a banner that is never coming. */
    private static final int BANNER_WAIT_MS = 300;

    private static final Pattern TITLE =
            Pattern.compile("<title>\\s*(.*?)\\s*</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CN = Pattern.compile("CN=([^,]+)");

    private ProtocolDetector() {}

    /**
     * What one probe of a port found: the protocol name (one of the labels
     * {@code ResourcePort.protocol} already models — SSH, VNC, HTTP, HTTPS,
     * RTSP, RDP), plus whatever the probe incidentally learned. Every field
     * but {@code protocol} may be null; nothing here is invented when the
     * probe did not actually see it.
     */
    public record Detection(String protocol, String title, String certCn, Instant certExpiry, Boolean nlaRequired) {
        static Detection of(String protocol) {
            return new Detection(protocol, null, null, null, null);
        }
    }

    public static Optional<Detection> detect(String ip, int port, Duration timeout) {
        Optional<Detection> banner = tryBanner(ip, port, timeout);
        if (banner.isPresent()) return banner;

        Optional<Detection> https = tryHttps(ip, port, timeout);
        if (https.isPresent()) return https;

        Optional<Detection> http = tryPlainRequest(ip, port, timeout,
                "GET / HTTP/1.0\r\nHost: " + ip + "\r\nConnection: close\r\n\r\n",
                "HTTP/1.", "HTTP");
        if (http.isPresent()) return http;

        Optional<Detection> rtsp = tryRtsp(ip, port, timeout);
        if (rtsp.isPresent()) return rtsp;

        return tryRdp(ip, port, timeout);
    }

    private static Optional<Detection> tryBanner(String ip, int port, Duration timeout) {
        try (Socket socket = connect(ip, port, timeout)) {
            socket.setSoTimeout((int) Math.min(Math.max(1, timeout.toMillis()), BANNER_WAIT_MS));
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[MAX_BANNER_BYTES];
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                return Optional.empty();
            }
            if (n <= 0) return Optional.empty();
            String banner = new String(buf, 0, n, StandardCharsets.US_ASCII);
            if (banner.startsWith(SSH_PREFIX)) {
                return Optional.of(new Detection("SSH", firstLine(banner), null, null, null));
            }
            if (banner.startsWith(RFB_PREFIX)) {
                return Optional.of(new Detection("VNC", firstLine(banner), null, null, null));
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Detection> tryHttps(String ip, int port, Duration timeout) {
        try (Socket raw = connect(ip, port, timeout)) {
            SSLSocketFactory factory = trustAllContext().getSocketFactory();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(raw, ip, port, false)) {
                ssl.setSoTimeout((int) Math.max(1, timeout.toMillis()));
                ssl.startHandshake();

                String cn = null;
                Instant expiry = null;
                Certificate[] certs = ssl.getSession().getPeerCertificates();
                if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                    cn = extractCn(x509);
                    expiry = x509.getNotAfter().toInstant();
                }
                String title = fetchTitle(ssl.getOutputStream(), ssl.getInputStream(), ip);
                return Optional.of(new Detection("HTTPS", title, cn, expiry, null));
            }
        } catch (Exception e) {
            // Handshake failure is exactly "not TLS here" for this purpose —
            // an expired/untrusted/self-signed cert is not a reason to fail
            // the *handshake* with a permissive trust manager, so anything
            // that does land here is a genuine non-TLS port.
            return Optional.empty();
        }
    }

    private static Optional<Detection> tryPlainRequest(String ip, int port, Duration timeout,
                                                         String request, String expectedPrefix, String protocol) {
        try (Socket socket = connect(ip, port, timeout)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] body = readBounded(socket.getInputStream(), MAX_BODY_BYTES);
            String text = new String(body, StandardCharsets.ISO_8859_1);
            if (!text.startsWith(expectedPrefix)) return Optional.empty();
            return Optional.of(new Detection(protocol, extractTitle(text), null, null, null));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Detection> tryRtsp(String ip, int port, Duration timeout) {
        String request = "OPTIONS rtsp://" + ip + ":" + port + "/ RTSP/1.0\r\nCSeq: 1\r\n\r\n";
        return tryPlainRequest(ip, port, timeout, request, "RTSP/1.0", "RTSP");
    }

    private static Optional<Detection> tryRdp(String ip, int port, Duration timeout) {
        // TPKT(4) + X.224 Connection Request TPDU(7) + RDP Negotiation
        // Request(8), requesting TLS and CredSSP/NLA (PROTOCOL_SSL | PROTOCOL_HYBRID = 0x03)
        // so a server that supports NLA says so in its response rather than
        // silently downgrading — the well-known 19-byte handshake opener
        // every RDP client sends first.
        byte[] request = {
                0x03, 0x00, 0x00, 0x13,
                0x0E, (byte) 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00
        };
        try (Socket socket = connect(ip, port, timeout)) {
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            byte[] response = readBounded(socket.getInputStream(), 64);
            if (response.length < 4 || response[0] != 0x03 || response[1] != 0x00) {
                return Optional.empty();
            }
            Boolean nlaRequired = null;
            // Negotiation Response, when present, starts right after the
            // TPKT header (4) and the X.224 CC TPDU header (7).
            int negOffset = 11;
            if (response.length >= negOffset + 8 && response[negOffset] == 0x02 /* TYPE_RDP_NEG_RSP */) {
                int selectedProtocol = response[negOffset + 4] & 0xFF; // little-endian, high bytes always 0 here
                nlaRequired = (selectedProtocol & 0x02) != 0; // PROTOCOL_HYBRID
            }
            return Optional.of(new Detection("RDP", null, null, null, nlaRequired));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Socket connect(String ip, int port, Duration timeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), (int) Math.max(1, timeout.toMillis()));
            socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        try {
            return in.readNBytes(max);
        } catch (SocketTimeoutException e) {
            return new byte[0];
        }
    }

    private static String fetchTitle(OutputStream out, InputStream in, String host) {
        try {
            out.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] body = readBounded(in, MAX_BODY_BYTES);
            return extractTitle(new String(body, StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return null;
        }
    }

    private static String extractTitle(String httpResponse) {
        Matcher m = TITLE.matcher(httpResponse);
        if (!m.find()) return null;
        String title = m.group(1).replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (title.length() > 120) title = title.substring(0, 120).trim();
        return title.isBlank() ? null : title;
    }

    private static String extractCn(X509Certificate cert) {
        Matcher m = CN.matcher(cert.getSubjectX500Principal().getName());
        return m.find() ? m.group(1) : null;
    }

    private static String firstLine(String banner) {
        int nl = banner.indexOf('\n');
        String line = nl >= 0 ? banner.substring(0, nl) : banner;
        return line.replace("\r", "").trim();
    }

    private static javax.net.ssl.SSLContext trustAllContext() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] { trustAll }, new SecureRandom());
        return ctx;
    }
}
