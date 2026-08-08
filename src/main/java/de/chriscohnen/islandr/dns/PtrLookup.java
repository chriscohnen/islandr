package de.chriscohnen.islandr.dns;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reverse-DNS (PTR) lookup against an explicitly configured DNS server —
 * used by device discovery ({@link de.chriscohnen.islandr.discovery.HostProbe})
 * to suggest a resource name from a site's local router (e.g. a FRITZ!Box),
 * which usually knows DHCP-registered hostnames the JVM's system resolver
 * has no route/config to ask for a remote site reached over the tunnel.
 *
 * <p>Same socket pattern as {@link DnsResolverService#queryUpstreamForPreview}:
 * one throwaway query, one timeout, best-effort — never throws, callers get
 * an empty {@link Optional} on any failure (timeout, malformed response,
 * unreachable server). No elevated privileges: a plain {@link DatagramSocket}
 * UDP operation, the same primitive class the discovery package's own
 * TCP-connect probes already use (ADR-0011/0014) — no {@code CAP_NET_RAW},
 * no {@code sudo}, no involvement of {@code islandr-proxy}.
 */
public final class PtrLookup {

    private PtrLookup() {}

    public static Optional<String> lookup(String targetIp, String dnsServerIp, Duration timeout) {
        return lookup(targetIp, dnsServerIp, 53, timeout);
    }

    /** Port-parameterized for testing against a fake local server; production
     *  callers always go through the 3-arg overload above (port 53). */
    static Optional<String> lookup(String targetIp, String dnsServerIp, int port, Duration timeout) {
        try {
            String reverseName = reverseArpaName(targetIp);
            int id = (int) (System.nanoTime() & 0xFFFF);
            byte[] query = DnsWireFormat.buildQuery(id, reverseName, DnsWireFormat.TYPE_PTR);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
                InetAddress server = InetAddress.getByName(dnsServerIp);
                socket.send(new DatagramPacket(query, query.length, server, port));

                byte[] buf = new byte[512];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);

                byte[] data = Arrays.copyOf(response.getData(), response.getLength());
                return Optional.ofNullable(DnsWireFormat.parseFirstPtrName(data));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String reverseArpaName(String ipv4) {
        String[] octets = ipv4.split("\\.");
        if (octets.length != 4) throw new IllegalArgumentException("not an IPv4 address: " + ipv4);
        return octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0] + ".in-addr.arpa";
    }
}
