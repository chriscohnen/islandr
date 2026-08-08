package de.chriscohnen.islandr.dns;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Socket-level tests against a fake local UDP DNS server — no real network,
 *  no port 53 (which is usually privileged/unavailable in a test sandbox);
 *  uses PtrLookup's package-private port-parameterized overload. */
class PtrLookupTest {

    @Test
    void lookup_returnsTheNameFromAWellFormedResponse() throws Exception {
        try (DatagramSocket fakeServer = new DatagramSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    fakeServer.receive(req);
                    DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(req.getData(), req.getLength());
                    byte[] resp = ptrResponse(parsed.id(), req.getData(), parsed.questionEnd(), "device.fritz.box");
                    fakeServer.send(new DatagramPacket(resp, resp.length, req.getAddress(), req.getPort()));
                } catch (Exception ignored) {
                    // test fails via the assertion below timing out / getting empty
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            Optional<String> result = PtrLookup.lookup("192.168.178.23", "127.0.0.1", port, Duration.ofSeconds(2));

            assertThat(result).contains("device.fritz.box");
        }
    }

    @Test
    void lookup_returnsEmpty_onTimeout() {
        // Nothing listening on this port — must time out gracefully, not throw.
        Optional<String> result = PtrLookup.lookup("192.168.178.23", "127.0.0.1", 1, Duration.ofMillis(300));

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
