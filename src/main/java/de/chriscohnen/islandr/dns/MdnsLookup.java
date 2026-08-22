package de.chriscohnen.islandr.dns;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * mDNS (RFC 6762) reverse hostname lookup — issue #48's first fallback when
 * {@link PtrLookup} (issue #45, router-registered DHCP hostnames only) comes
 * up empty. Devices that advertise their own name over multicast DNS
 * (macOS/Linux/most IoT gear) answer a reverse ("*.in-addr.arpa") PTR query
 * with their own "<name>.local." — no router-side registration needed, only
 * the device itself has to be running an mDNS responder.
 *
 * <p>Same fire-and-forget UDP posture as {@link PtrLookup}: a single query
 * sent to the mDNS multicast group ({@code 224.0.0.251:5353}) with the "QU"
 * bit set on the question (RFC 6762 §5.4) to ask the responder to reply
 * <em>unicast</em> straight back to our ephemeral port, so this stays a
 * plain, unprivileged {@link DatagramSocket} round trip — no multicast group
 * join, no {@code CAP_NET_RAW}, no {@code islandr-proxy} involvement
 * (ADR-0011/0014, same privilege posture as #45). Best-effort: not every
 * responder honors QU, so a false negative here just falls through to
 * {@link NetBiosLookup} — never treated as an error.
 */
public final class MdnsLookup {

    static final String MULTICAST_GROUP = "224.0.0.251";
    static final int MDNS_PORT = 5353;

    /** RFC 6762 §5.4: top bit of QCLASS on the question requests a unicast reply. */
    private static final int QU_BIT = 0x8000;

    private MdnsLookup() {}

    public static Optional<String> lookup(String targetIp, Duration timeout) {
        return lookup(targetIp, MULTICAST_GROUP, MDNS_PORT, timeout);
    }

    /** Port/host-parameterized for testing against a fake local responder;
     *  production callers always go through the 2-arg overload above. */
    static Optional<String> lookup(String targetIp, String mdnsHost, int port, Duration timeout) {
        try {
            String reverseName = PtrLookup.reverseArpaName(targetIp);
            int id = (int) (System.nanoTime() & 0xFFFF);
            byte[] query = DnsWireFormat.buildQuery(id, reverseName, DnsWireFormat.TYPE_PTR,
                    DnsWireFormat.CLASS_IN | QU_BIT);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
                InetAddress group = InetAddress.getByName(mdnsHost);
                socket.send(new DatagramPacket(query, query.length, group, port));

                byte[] buf = new byte[512];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);

                byte[] data = Arrays.copyOf(response.getData(), response.getLength());
                String name = DnsWireFormat.parseFirstPtrName(data);
                return Optional.ofNullable(stripLocalSuffix(name));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** mDNS names end in ".local." — the raw PTR target is a fine reverse-DNS
     *  name but a noisy resource-name suggestion; strip the domain suffix the
     *  same way a router-registered PTR name (which has none) already reads. */
    private static String stripLocalSuffix(String name) {
        if (name == null) return null;
        String trimmed = name;
        while (trimmed.endsWith(".")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.regionMatches(true, trimmed.length() - 6, ".local", 0, 6)) {
            trimmed = trimmed.substring(0, trimmed.length() - 6);
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
