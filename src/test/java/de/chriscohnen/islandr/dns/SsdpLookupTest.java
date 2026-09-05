package de.chriscohnen.islandr.dns;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Socket-level tests against a fake local device: a UDP responder that answers
 * M-SEARCH with a LOCATION header, and an HTTP server serving the description
 * that header points at.
 */
class SsdpLookupTest {

    private static final String DESCRIPTION = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:Printer:1</deviceType>
                <friendlyName>Brother HL-L2350DW</friendlyName>
              </device>
            </root>
            """;

    @Test
    void lookup_returnsTheFriendlyNameFromTheDeviceDescription() throws Exception {
        HttpServer http = serve(DESCRIPTION, 200);
        try (DatagramSocket ssdp = new DatagramSocket(0)) {
            respondWithLocation(ssdp, "http://127.0.0.1:" + http.getAddress().getPort() + "/desc.xml");

            Optional<String> result = SsdpLookup.lookup(
                    "10.83.1.50", "127.0.0.1", ssdp.getLocalPort(), Duration.ofSeconds(4));

            assertThat(result).contains("Brother HL-L2350DW");
        } finally {
            http.stop(0);
        }
    }

    @Test
    void lookup_refusesADescriptionHostedSomewhereElse() throws Exception {
        // A LOCATION pointing off-device would turn a LAN scan into an outbound
        // request to a third party — refused, not followed.
        HttpServer http = serve(DESCRIPTION, 200);
        try (DatagramSocket ssdp = new DatagramSocket(0)) {
            respondWithLocation(ssdp, "http://example.com:" + http.getAddress().getPort() + "/desc.xml");

            Optional<String> result = SsdpLookup.lookup(
                    "10.83.1.51", "127.0.0.1", ssdp.getLocalPort(), Duration.ofSeconds(2));

            assertThat(result).isEmpty();
        } finally {
            http.stop(0);
        }
    }

    @Test
    void lookup_collapsesWhitespaceAndCapsTheLength() throws Exception {
        String noisy = "<root><device><friendlyName>  Kitchen\n\tSpeaker  </friendlyName></device></root>";
        HttpServer http = serve(noisy, 200);
        try (DatagramSocket ssdp = new DatagramSocket(0)) {
            respondWithLocation(ssdp, "http://127.0.0.1:" + http.getAddress().getPort() + "/desc.xml");

            Optional<String> result = SsdpLookup.lookup(
                    "10.83.1.52", "127.0.0.1", ssdp.getLocalPort(), Duration.ofSeconds(4));

            assertThat(result).contains("Kitchen Speaker");
        } finally {
            http.stop(0);
        }
    }

    @Test
    void lookup_returnsEmpty_whenTheDescriptionHasNoFriendlyName() throws Exception {
        HttpServer http = serve("<root><device><deviceType>x</deviceType></device></root>", 200);
        try (DatagramSocket ssdp = new DatagramSocket(0)) {
            respondWithLocation(ssdp, "http://127.0.0.1:" + http.getAddress().getPort() + "/desc.xml");

            Optional<String> result = SsdpLookup.lookup(
                    "10.83.1.53", "127.0.0.1", ssdp.getLocalPort(), Duration.ofSeconds(4));

            assertThat(result).isEmpty();
        } finally {
            http.stop(0);
        }
    }

    @Test
    void lookup_returnsEmpty_whenNothingAnswersTheSearch() {
        Optional<String> result = SsdpLookup.lookup("10.83.1.54", "127.0.0.1", 1, Duration.ofMillis(400));
        assertThat(result).isEmpty();
    }

    private static HttpServer serve(String body, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/desc.xml", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void respondWithLocation(DatagramSocket socket, String location) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[2048];
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                socket.receive(req);
                String reply = "HTTP/1.1 200 OK\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: " + location + "\r\n"
                        + "SERVER: Linux/4.4 UPnP/1.0 Device/1.0\r\n"
                        + "ST: upnp:rootdevice\r\n\r\n";
                byte[] out = reply.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(out, out.length, req.getAddress(), req.getPort()));
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
    }
}
