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

    @Test
    void nodeStatus_returnsTheUnitIdMacFromTheStatisticsBlock() throws Exception {
        // RFC 1002 §4.2.18: the STATISTICS block that follows the name table
        // opens with a 6-byte UNIT_ID — the target's MAC address. Same packet
        // the name already comes from, so no extra query (issue #76).
        byte[] response = nbstatResponseWithStatistics(0, "NAS-BASEMENT",
                new byte[]{(byte) 0x00, (byte) 0x1a, (byte) 0x2b, (byte) 0x3c, (byte) 0x4d, (byte) 0x5e});

        Optional<NetBiosLookup.NodeStatus> result = respondWith(response);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("NAS-BASEMENT");
        assertThat(result.get().mac()).isEqualTo("00:1a:2b:3c:4d:5e");
    }

    @Test
    void nodeStatus_returnsTheNameWithoutAMac_whenTheStatisticsBlockIsMissing() throws Exception {
        // Old/minimal responders that stop after the name table must still
        // yield their name — the MAC is an optional extra, never a precondition.
        byte[] response = nbstatResponse(0, "DESKTOP-WIN10");

        Optional<NetBiosLookup.NodeStatus> result = respondWith(response);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("DESKTOP-WIN10");
        assertThat(result.get().mac()).isNull();
    }

    @Test
    void nodeStatus_reportsNoMac_whenTheUnitIdIsAllZeroes() throws Exception {
        // An all-zero UNIT_ID is what a NetBIOS-over-TCP-only stack sends when
        // it has no hardware address to report — not a real MAC (same posture
        // as ArpCache's null-MAC filter).
        byte[] response = nbstatResponseWithStatistics(0, "SAMBA-VM", new byte[6]);

        Optional<NetBiosLookup.NodeStatus> result = respondWith(response);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("SAMBA-VM");
        assertThat(result.get().mac()).isNull();
    }

    private static byte[] nbstatResponse(int id, String computerName) {
        return nbstatResponse(id, computerName, new byte[0]);
    }

    private static byte[] nbstatResponse(int id, String computerName, byte[] statistics) {
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
        rdata.write(statistics, 0, statistics.length);
        byte[] rdataBytes = rdata.toByteArray();
        writeU16(out, rdataBytes.length);
        out.write(rdataBytes, 0, rdataBytes.length);
        return out.toByteArray();
    }

    /** Runs one lookup against a throwaway local responder that answers with
     *  {@code response} (its transaction id patched to the request's). */
    private static Optional<NetBiosLookup.NodeStatus> respondWith(byte[] response) throws Exception {
        try (DatagramSocket fakeResponder = new DatagramSocket(0)) {
            int port = fakeResponder.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    fakeResponder.receive(req);
                    response[0] = req.getData()[0];
                    response[1] = req.getData()[1];
                    fakeResponder.send(new DatagramPacket(response, response.length, req.getAddress(), req.getPort()));
                } catch (Exception ignored) {
                    // test fails via the assertion below getting empty
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
            return NetBiosLookup.nodeStatus("192.168.178.55", "127.0.0.1", port, Duration.ofSeconds(2));
        }
    }

    /** Name table plus the RFC 1002 §4.2.18 STATISTICS block, whose first six
     *  bytes are the UNIT_ID (MAC); the remaining counters are zero-filled. */
    private static byte[] nbstatResponseWithStatistics(int id, String computerName, byte[] unitId) {
        ByteArrayOutputStream stats = new ByteArrayOutputStream();
        stats.write(unitId, 0, 6);
        for (int i = 0; i < 40; i++) stats.write(0); // jumpers/test result/counters
        return nbstatResponse(id, computerName, stats.toByteArray());
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
