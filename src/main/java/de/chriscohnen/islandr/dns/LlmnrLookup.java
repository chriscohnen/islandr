package de.chriscohnen.islandr.dns;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * LLMNR (RFC 4795) reverse hostname lookup — the name source for Windows hosts
 * that no DNS server knows and that answer neither mDNS nor NetBIOS.
 *
 * <p>LLMNR is what Windows falls back to for local name resolution, and it is
 * enabled by default on every current Windows build. Its wire format is
 * deliberately DNS (RFC 4795 §2.1), so this reuses {@link DnsWireFormat}
 * unchanged — it is the same query {@link MdnsLookup} sends, aimed at a
 * different port. What differs from mDNS is the name shape: LLMNR names are
 * single labels with no {@code .local} suffix to strip.
 *
 * <p>Sent <b>unicast</b> to the host being probed, for the same reason
 * {@link MdnsLookup} prefers unicast: the scan already knows the address, and
 * LLMNR's multicast group ({@code 224.0.0.252}) never crosses a router, which
 * would make it useless for every host behind a site gateway. RFC 4795 §2.1
 * allows a unicast query; a responder answers it from port 5355.
 *
 * <p>Best-effort like every other source in the chain: an unanswered probe, a
 * malformed reply or a timeout all return empty rather than throwing. A name
 * learned here is device-self-reported — it is what the host calls itself, not
 * what any authority registered for it.
 */
public final class LlmnrLookup {

    static final int LLMNR_PORT = 5355;

    private LlmnrLookup() {}

    public static Optional<String> lookup(String targetIp, Duration timeout) {
        return lookup(targetIp, targetIp, LLMNR_PORT, timeout);
    }

    /** Host/port-parameterized for testing against a fake local responder;
     *  production callers always go through the 2-arg overload above. */
    static Optional<String> lookup(String targetIp, String host, int port, Duration timeout) {
        try {
            String reverseName = PtrLookup.reverseArpaName(targetIp);
            int id = (int) (System.nanoTime() & 0xFFFF);
            byte[] query = DnsWireFormat.buildQuery(id, reverseName, DnsWireFormat.TYPE_PTR,
                    DnsWireFormat.CLASS_IN);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
                socket.send(new DatagramPacket(query, query.length,
                        InetAddress.getByName(host), port));

                byte[] buf = new byte[512];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);

                byte[] data = Arrays.copyOf(response.getData(), response.getLength());
                return Optional.ofNullable(normalize(DnsWireFormat.parseFirstPtrName(data)));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** LLMNR answers a single label, but a responder may still terminate it
     *  with the DNS root dot. Nothing else to strip — unlike mDNS there is no
     *  domain suffix. */
    private static String normalize(String name) {
        if (name == null) return null;
        String trimmed = name;
        while (trimmed.endsWith(".")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isBlank() ? null : trimmed;
    }
}
