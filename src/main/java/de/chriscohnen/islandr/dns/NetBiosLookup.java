package de.chriscohnen.islandr.dns;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * NetBIOS Name Service (RFC 1002 §4.2, "NBSTAT"/node status) reverse hostname
 * lookup — issue #48's second fallback, after {@link PtrLookup} (#45) and
 * {@link MdnsLookup} both come up empty. Legacy, but still how a lot of
 * Windows LAN devices announce a name when no mDNS responder is running.
 *
 * <p>Same posture as {@link PtrLookup}/{@link MdnsLookup}: one throwaway UDP
 * query to port 137, one timeout, best-effort — never throws, empty
 * {@link Optional} on any failure. Plain {@link DatagramSocket}, no
 * {@code CAP_NET_RAW}, no {@code sudo}, no {@code islandr-proxy}
 * (ADR-0011/0014).
 */
public final class NetBiosLookup {

    static final int NBNS_PORT = 137;
    private static final int NBSTAT_QTYPE = 0x21;
    private static final int CLASS_IN = 1;
    private static final int GROUP_FLAG = 0x8000;

    private static final String NULL_MAC = "00:00:00:00:00:00";
    /** Fixed size of one NBSTAT name-table entry: 15-byte name + suffix + 2 flag bytes. */
    private static final int NAME_ENTRY_LEN = 18;
    /** Length of the STATISTICS block's leading UNIT_ID field (RFC 1002 §4.2.18). */
    private static final int UNIT_ID_LEN = 6;

    private NetBiosLookup() {}

    /**
     * What one NBSTAT response says about a host: its NetBIOS name and, when
     * the responder included a STATISTICS block, the MAC address from that
     * block's UNIT_ID field. Either field may be null; an all-empty response
     * yields an empty {@link Optional} instead of a record of two nulls.
     */
    public record NodeStatus(String name, String mac) {}

    public static Optional<String> lookup(String targetIp, Duration timeout) {
        return lookup(targetIp, targetIp, NBNS_PORT, timeout);
    }

    /**
     * Full node status — name <em>and</em> MAC — from a single NBSTAT query
     * (issue #76). Unlike ARP, this is not link-scoped: NBSTAT is an ordinary
     * unicast query, so it reaches a host behind a site gateway just as well
     * as one on the hub's own segment. It only covers hosts that speak
     * NetBIOS at all (Windows shares, Samba NAS boxes, some printers).
     */
    public static Optional<NodeStatus> nodeStatus(String targetIp, Duration timeout) {
        return nodeStatus(targetIp, targetIp, NBNS_PORT, timeout);
    }

    /** Host/port-parameterized for testing against a fake local responder —
     *  {@code destinationHost} is where the query is actually sent, decoupled
     *  from {@code targetIp} (used only for logging/identity, not addressing,
     *  in that test path); production callers always go through the 2-arg
     *  overload above where the two are the same host. */
    static Optional<String> lookup(String targetIp, String destinationHost, int port, Duration timeout) {
        return nodeStatus(targetIp, destinationHost, port, timeout).map(NodeStatus::name);
    }

    /** Host/port-parameterized {@link #nodeStatus(String, Duration)} — same test seam
     *  as the {@link #lookup} overload above. */
    static Optional<NodeStatus> nodeStatus(String targetIp, String destinationHost, int port, Duration timeout) {
        try {
            int id = (int) (System.nanoTime() & 0xFFFF);
            byte[] query = buildNbstatQuery(id);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
                InetAddress target = InetAddress.getByName(destinationHost);
                socket.send(new DatagramPacket(query, query.length, target, port));

                byte[] buf = new byte[1024];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);

                return parseNodeStatus(response.getData(), response.getLength());
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The wildcard NBSTAT query name: one {@code '*'} followed by 15 NUL
     *  bytes (RFC 1002's documented convention for a node-status query,
     *  distinct from an ordinary padded-with-spaces NetBIOS name), first-
     *  level-encoded into 32 ASCII bytes (each nibble mapped to a letter
     *  {@code 'A'..'P'}, RFC 1001 §14.1). */
    private static byte[] buildNbstatQuery(int id) {
        byte[] rawName = new byte[16];
        rawName[0] = '*';
        // remaining 15 bytes stay 0x00

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, 0x0000); // standard query, no recursion — unicast to a known host
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 0); // ANCOUNT
        writeU16(out, 0); // NSCOUNT
        writeU16(out, 0); // ARCOUNT
        out.write(32); // encoded name length
        for (byte b : rawName) {
            int v = b & 0xff;
            out.write('A' + ((v >> 4) & 0x0F));
            out.write('A' + (v & 0x0F));
        }
        out.write(0); // root label
        writeU16(out, NBSTAT_QTYPE);
        writeU16(out, CLASS_IN);
        return out.toByteArray();
    }

    /**
     * Reads both fields the NBSTAT answer carries: the computer name from the
     * name table, and the MAC address from the UNIT_ID that opens the
     * STATISTICS block right behind it.
     *
     * <p>The name is the first unique (non-group) workstation-suffix (0x00)
     * entry, trimmed of its space padding, falling back to the first unique
     * entry of any suffix. Fails safe (empty/null) on anything
     * malformed/unexpected — this parses bytes from a network peer, same
     * posture as {@link DnsWireFormat#parseFirstPtrName}.
     */
    private static Optional<NodeStatus> parseNodeStatus(byte[] data, int length) {
        NodeStatus status = parseNodeStatusOrNull(data, length);
        return status == null || (status.name() == null && status.mac() == null)
                ? Optional.empty() : Optional.of(status);
    }

    private static NodeStatus parseNodeStatusOrNull(byte[] data, int length) {
        try {
            if (length < 12) return null;
            int qdcount = u16(data, 4);
            int pos = 12;
            // Most real responders send QDCOUNT=0 (no echoed question), unlike
            // a PTR/A response — but skip any that are present rather than
            // assume a fixed shape either way.
            for (int i = 0; i < qdcount; i++) {
                pos = DnsWireFormat.skipName(data, pos);
                pos += 4; // QTYPE + QCLASS
            }
            if (pos + 10 > length) return null;
            pos = DnsWireFormat.skipName(data, pos); // RR NAME (often a compression pointer)
            int type = u16(data, pos); pos += 2;
            pos += 2; // CLASS
            pos += 4; // TTL
            int rdlength = u16(data, pos); pos += 2;
            if (type != NBSTAT_QTYPE || pos + rdlength > length || rdlength < 1) return null;

            int rdataEnd = pos + rdlength;
            int numNames = data[pos] & 0xff;
            pos += 1;
            String name = null;
            String fallback = null;
            for (int i = 0; i < numNames; i++) {
                int entryStart = pos + i * NAME_ENTRY_LEN;
                if (entryStart + NAME_ENTRY_LEN > length) break;
                String entry = new String(data, entryStart, 15, StandardCharsets.US_ASCII).trim();
                int suffix = data[entryStart + 15] & 0xff;
                int flags = u16(data, entryStart + 16);
                boolean isGroup = (flags & GROUP_FLAG) != 0;
                if (isGroup || entry.isEmpty()) continue;
                if (suffix == 0x00) { name = entry; break; }
                if (fallback == null) fallback = entry;
            }
            if (name == null) name = fallback;
            return new NodeStatus(name, parseUnitIdMac(data, pos + numNames * NAME_ENTRY_LEN, Math.min(rdataEnd, length)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The six bytes at the head of the STATISTICS block are the responder's
     * hardware address (RFC 1002 §4.2.18) — the same field {@code nbtstat -A}
     * prints as "MAC Address". Responders that stop after the name table, and
     * NetBIOS-over-TCP-only stacks that report an all-zero UNIT_ID, simply
     * have no MAC to give: null, never a fabricated one.
     */
    private static String parseUnitIdMac(byte[] data, int statisticsStart, int rdataEnd) {
        if (statisticsStart < 0 || statisticsStart + UNIT_ID_LEN > rdataEnd) return null;
        StringBuilder mac = new StringBuilder(17);
        for (int i = 0; i < UNIT_ID_LEN; i++) {
            if (i > 0) mac.append(':');
            mac.append(String.format("%02x", data[statisticsStart + i] & 0xff));
        }
        String out = mac.toString();
        return NULL_MAC.equals(out) ? null : out;
    }

    private static int u16(byte[] d, int off) {
        return ((d[off] & 0xff) << 8) | (d[off + 1] & 0xff);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }
}
