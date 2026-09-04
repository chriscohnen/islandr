package de.chriscohnen.islandr.dns;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Socket-level tests against a fake local UDP responder — no real multicast,
 *  no port 5355; uses the package-private host/port-parameterized overload. */
class LlmnrLookupTest {

    @Test
    void lookup_returnsTheSingleLabelName() throws Exception {
        try (DatagramSocket fake = new DatagramSocket(0)) {
            respondOnce(fake, "WEBSERVATORY-TR");

            Optional<String> result = LlmnrLookup.lookup(
                    "10.83.1.132", "127.0.0.1", fake.getLocalPort(), Duration.ofSeconds(2));

            assertThat(result).contains("WEBSERVATORY-TR");
        }
    }

    @Test
    void lookup_stripsTheRootDot_butKeepsEverythingElse() throws Exception {
        // Unlike mDNS there is no ".local" to remove — a name containing dots
        // must survive intact.
        try (DatagramSocket fake = new DatagramSocket(0)) {
            respondOnce(fake, "host.with.dots.");

            Optional<String> result = LlmnrLookup.lookup(
                    "10.83.1.9", "127.0.0.1", fake.getLocalPort(), Duration.ofSeconds(2));

            assertThat(result).contains("host.with.dots");
        }
    }

    @Test
    void lookup_returnsEmpty_onTimeout() {
        Optional<String> result = LlmnrLookup.lookup("10.83.1.7", "127.0.0.1", 1, Duration.ofMillis(300));
        assertThat(result).isEmpty();
    }

    @Test
    void lookup_returnsEmpty_onGarbageResponse() throws Exception {
        try (DatagramSocket fake = new DatagramSocket(0)) {
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    fake.receive(req);
                    byte[] junk = "not a dns message".getBytes();
                    fake.send(new DatagramPacket(junk, junk.length, req.getAddress(), req.getPort()));
                } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();

            Optional<String> result = LlmnrLookup.lookup(
                    "10.83.1.8", "127.0.0.1", fake.getLocalPort(), Duration.ofSeconds(2));

            assertThat(result).isEmpty();
        }
    }

    private static void respondOnce(DatagramSocket socket, String name) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[512];
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                socket.receive(req);
                DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(req.getData(), req.getLength());
                byte[] resp = MdnsLookupTest.ptrResponse(parsed.id(), req.getData(), parsed.questionEnd(), name);
                socket.send(new DatagramPacket(resp, resp.length, req.getAddress(), req.getPort()));
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
    }
}
