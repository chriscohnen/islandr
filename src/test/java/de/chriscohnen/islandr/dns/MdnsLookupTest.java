package de.chriscohnen.islandr.dns;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Socket-level tests against a fake local UDP mDNS responder — no real
 *  multicast group, no port 5353; uses MdnsLookup's package-private
 *  host/port-parameterized overload. */
class MdnsLookupTest {

    @Test
    void lookup_stripsTheLocalSuffixFromAWellFormedResponse() throws Exception {
        try (DatagramSocket fakeResponder = new DatagramSocket(0)) {
            int port = fakeResponder.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    fakeResponder.receive(req);
                    DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(req.getData(), req.getLength());
                    byte[] resp = ptrResponse(parsed.id(), req.getData(), parsed.questionEnd(), "macbook-pro.local");
                    fakeResponder.send(new DatagramPacket(resp, resp.length, req.getAddress(), req.getPort()));
                } catch (Exception ignored) {
                    // test fails via the assertion below timing out / getting empty
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            Optional<String> result = MdnsLookup.lookup("192.168.178.42", "127.0.0.1", port, Duration.ofSeconds(2));

            assertThat(result).contains("macbook-pro");
        }
    }

    @Test
    void lookup_returnsEmpty_onTimeout() {
        // Nothing listening on this port — must time out gracefully, not throw.
        Optional<String> result = MdnsLookup.lookup("192.168.178.42", "127.0.0.1", 1, Duration.ofMillis(300));

        assertThat(result).isEmpty();
    }

    private static byte[] ptrResponse(int id, byte[] queryData, int questionEnd, String targetName) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, 0x8180);
        writeU16(out, 1);
        writeU16(out, 1);
        writeU16(out, 0);
        writeU16(out, 0);
        out.write(queryData, 12, questionEnd - 12);
        writeU16(out, 0xC00C);
        writeU16(out, DnsWireFormat.TYPE_PTR);
        writeU16(out, DnsWireFormat.CLASS_IN);
        out.write(0); out.write(0); out.write(0); out.write(30);
        java.io.ByteArrayOutputStream rdata = new java.io.ByteArrayOutputStream();
        for (String label : targetName.split("\\.")) {
            byte[] l = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            rdata.write(l.length);
            rdata.write(l);
        }
        rdata.write(0);
        byte[] rdataBytes = rdata.toByteArray();
        writeU16(out, rdataBytes.length);
        out.write(rdataBytes);
        return out.toByteArray();
    }

    private static void writeU16(java.io.ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }
}
