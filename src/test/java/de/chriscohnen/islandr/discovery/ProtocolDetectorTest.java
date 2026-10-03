package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single fake service on a loopback socket per test, playing the far end of
 * whichever protocol is under test — the same "genuine, not mocked" style as
 * {@link PortScannerTest}. Each fixture is deliberately minimal: exactly the
 * bytes that make {@link ProtocolDetector} decide, nothing a real device would
 * also send.
 */
class ProtocolDetectorTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    @Test
    void recognizesSshFromItsBanner() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            accept(server, socket -> {
                socket.getOutputStream().write("SSH-2.0-OpenSSH_9.6\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            });

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("SSH");
            assertThat(d.get().title()).startsWith("SSH-2.0-OpenSSH_9.6");
        }
    }

    @Test
    void recognizesVncFromItsRfbBanner() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            accept(server, socket -> {
                socket.getOutputStream().write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            });

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("VNC");
        }
    }

    @Test
    void recognizesPlainHttpAndExtractsTitle() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            acceptHttpLike(server, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n"
                    + "<html><head><title>Device Admin</title></head><body></body></html>");

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("HTTP");
            assertThat(d.get().title()).isEqualTo("Device Admin");
        }
    }

    @Test
    void recognizesHttpsAndReadsTheCertificate() throws Exception {
        SslFixture tls = SslFixture.generate();
        try (SSLServerSocket server = tls.serverSocket()) {
            acceptHttpsLike(server, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n"
                    + "<html><head><title>Kamera</title></head><body></body></html>");

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("HTTPS");
            assertThat(d.get().title()).isEqualTo("Kamera");
            assertThat(d.get().certCn()).isEqualTo("device.local");
            assertThat(d.get().certExpiry()).isNotNull();
        }
    }

    @Test
    void recognizesRtspFromAnOptionsReply() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            acceptHttpLike(server, "RTSP/1.0 200 OK\r\nCSeq: 1\r\n"
                    + "Public: OPTIONS, DESCRIBE, SETUP, PLAY\r\n\r\n");

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("RTSP");
        }
    }

    @Test
    void recognizesRdpAndReadsWhetherNlaIsRequired() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            accept(server, socket -> {
                readFully(socket, 19); // the fixed-size X.224 negotiation request
                socket.getOutputStream().write(rdpNegotiationResponse(0x02)); // PROTOCOL_HYBRID = NLA
                socket.getOutputStream().flush();
            });

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("RDP");
            assertThat(d.get().nlaRequired()).isTrue();
        }
    }

    @Test
    void recognizesRdpWithoutNlaFromASuccessfulTlsNegotiation() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            accept(server, socket -> {
                readFully(socket, 19);
                socket.getOutputStream().write(rdpNegotiationResponse(0x01)); // PROTOCOL_SSL, no HYBRID bit
                socket.getOutputStream().flush();
            });

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isPresent();
            assertThat(d.get().protocol()).isEqualTo("RDP");
            assertThat(d.get().nlaRequired()).isFalse();
        }
    }

    @Test
    void aPortThatAnswersNothingRecognizableYieldsNoDetection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            accept(server, socket -> { /* accepts, then says nothing at all */ });

            Optional<ProtocolDetector.Detection> d =
                    ProtocolDetector.detect("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertThat(d).isEmpty();
        }
    }

    // --- fixtures --------------------------------------------------------

    private interface ServerAction {
        void run(Socket socket) throws IOException;
    }

    /**
     * Detection tries several protocols in sequence, each against a fresh
     * connection — the banner-first attempt alone opens and abandons one
     * before an ask-first probe ever gets a chance. So the fixture keeps
     * accepting for as long as the port stays open, handling each connection
     * independently, rather than serving exactly one and then going away.
     */
    private static void accept(ServerSocket server, ServerAction action) {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    return; // server closed — the test is done with this fixture
                }
                try (socket) {
                    action.run(socket);
                } catch (IOException e) {
                    // this one connection ended early (e.g. the client gave up
                    // before this fixture had anything to say) — keep serving
                }
            }
        });
    }

    /** Reads one request line + headers (until a blank line, or the client
     *  gives up and disconnects first), then replies — unless there was
     *  nothing to reply to, matching a banner-first probe that never asks. */
    private static void acceptHttpLike(ServerSocket server, String response) {
        accept(server, socket -> {
            if (!readRequestHeaders(socket)) return;
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        });
    }

    private static void acceptHttpsLike(SSLServerSocket server, String response) {
        accept(server, socket -> {
            if (!readRequestHeaders(socket)) return;
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        });
    }

    /** @return true once a request line was actually read; false when the
     *  client (a banner-first probe that sends nothing) gave up first. */
    private static boolean readRequestHeaders(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String first = in.readLine();
        if (first == null) return false;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // drain the rest of the request; not asserted on here
        }
        return true;
    }

    private static void readFully(Socket socket, int n) {
        try {
            socket.getInputStream().readNBytes(n);
        } catch (IOException ignored) {
        }
    }

    /** TPKT(4) + X.224 Connection Confirm(7) + RDP Negotiation Response(8). */
    private static byte[] rdpNegotiationResponse(int selectedProtocol) {
        return new byte[] {
                0x03, 0x00, 0x00, 0x13,                   // TPKT: version, reserved, length=19
                0x0E, (byte) 0xD0, 0x00, 0x00, 0x00, 0x00, 0x00, // X.224 CC TPDU
                0x02, 0x00, 0x08, 0x00,                   // RDP_NEG_RSP, flags, length=8
                (byte) selectedProtocol, 0x00, 0x00, 0x00 // selectedProtocol, little-endian
        };
    }

    private record SslFixture(KeyStore keyStore, char[] password) {
        static SslFixture generate() throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            java.security.KeyPair pair = kpg.generateKeyPair();

            // Self-signed cert via the JDK's own certificate generator (no
            // extra dependency): sun.security.tools is not on the module
            // path from application code, so this shells out to the JDK's
            // own keytool — a real X.509 cert from the real toolchain,
            // exercising the actual JSSE handshake path rather than a stub.
            java.nio.file.Path ks = java.nio.file.Files.createTempFile("protocol-detector-test", ".p12");
            java.nio.file.Files.deleteIfExists(ks);
            String javaHome = System.getProperty("java.home");
            ProcessBuilder pb = new ProcessBuilder(
                    javaHome + "/bin/keytool", "-genkeypair",
                    "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "1",
                    "-dname", "CN=device.local",
                    "-keystore", ks.toString(), "-storetype", "PKCS12",
                    "-storepass", "changeit", "-keypass", "changeit"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            if (exit != 0) throw new IllegalStateException("keytool failed with exit " + exit);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var fis = java.nio.file.Files.newInputStream(ks)) {
                keyStore.load(fis, "changeit".toCharArray());
            }
            java.nio.file.Files.deleteIfExists(ks);
            return new SslFixture(keyStore, "changeit".toCharArray());
        }

        SSLServerSocket serverSocket() throws Exception {
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
            SSLServerSocketFactory factory = ctx.getServerSocketFactory();
            return (SSLServerSocket) factory.createServerSocket(0);
        }
    }
}
