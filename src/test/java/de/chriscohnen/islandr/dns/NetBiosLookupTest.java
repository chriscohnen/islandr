package de.chriscohnen.islandr.dns;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Socket-level tests against a fake local UDP NBSTAT responder — no real
 *  network, no port 137 (privileged/unavailable in a test sandbox); uses
 *  NetBiosLookup's package-private port-parameterized overload. */
class NetBiosLookupTest {

    @Test
    void lookup_returnsTheWorkstationNameFromAWellFormedResponse() throws Exception {
        try (DatagramSocket fakeResponder = new DatagramSocket(0)) {
            int port = fakeResponder.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    fakeResponder.receive(req);
                    int id = ((req.getData()[0] & 0xff) << 8) | (req.getData()[1] & 0xff);
                    byte[] resp = nbstatResponse(id, "DESKTOP-WIN10");
                    fakeResponder.send(new DatagramPacket(resp, resp.length, req.getAddress(), req.getPort()));
                } catch (Exception ignored) {
                    // test fails via the assertion below timing out / getting empty
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            Optional<String> result = NetBiosLookup.lookup("192.168.178.55", "127.0.0.1", port, Duration.ofSeconds(2));

            assertThat(result).contains("DESKTOP-WIN10");
        }
    }

    @Test
    void lookup_returnsEmpty_onTimeout() {
        // Nothing listening on this port — must time out gracefully, not throw.
        Optional<String> result = NetBiosLookup.lookup("192.168.178.55", "127.0.0.1", 1, Duration.ofMillis(300));

        assertThat(result).isEmpty();
    }

    private static byte[] nbstatResponse(int id, String computerName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, 0x8400); // response, authoritative
        writeU16(out, 0); // QDCOUNT
        writeU16(out, 1); // ANCOUNT
        writeU16(out, 0);
        writeU16(out, 0);
        // RR NAME: pointer back to offset 12 would need an echoed question we
        // didn't send here — use a trivial literal root name instead (this
        // parser only cares about skipping past it, not its content).
        out.write(0);
        writeU16(out, 0x21); // TYPE = NBSTAT
        writeU16(out, 1); // CLASS = IN
        out.write(0); out.write(0); out.write(0); out.write(0); // TTL
        ByteArrayOutputStream rdata = new ByteArrayOutputStream();
        rdata.write(1); // NUM_NAMES
        byte[] nameBytes = padTo15(computerName);
        rdata.write(nameBytes, 0, 15);
        rdata.write(0x00); // suffix: workstation
        writeU16(rdata, 0x0400); // flags: unique (no GROUP bit)
        byte[] rdataBytes = rdata.toByteArray();
        writeU16(out, rdataBytes.length);
        out.write(rdataBytes, 0, rdataBytes.length);
        return out.toByteArray();
    }

    private static byte[] padTo15(String name) {
        byte[] out = new byte[15];
        java.util.Arrays.fill(out, (byte) ' ');
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, out, 0, Math.min(n.length, 15));
        return out;
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }
}
