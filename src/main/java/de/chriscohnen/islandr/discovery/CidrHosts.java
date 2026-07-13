package de.chriscohnen.islandr.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the host addresses of an IPv4 CIDR for a discovery scan (ADR-0014).
 *
 * <p>Excludes the network and broadcast addresses (except /31 and /32, per
 * RFC 3021) and caps the range at {@link #MAX_HOSTS} so an operator cannot point
 * the scanner at a huge block. IPv6 is out of scope — a /64 is not enumerable —
 * so an IPv6 or malformed CIDR throws {@link IllegalArgumentException}, which the
 * REST layer maps to a 4xx.
 */
public final class CidrHosts {

    private CidrHosts() {}

    /** Largest range the scanner will enumerate (a /22). ADR-0014 caps the scan. */
    public static final int MAX_HOSTS = 1024;

    public static List<String> hosts(String cidr) {
        if (cidr == null) throw new IllegalArgumentException("cidr is null");
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) throw new IllegalArgumentException("not a CIDR: " + cidr);

        long base = ipv4ToLong(parts[0]);
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad prefix: " + cidr);
        }
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("prefix out of range: " + cidr);

        long size = 1L << (32 - prefix);
        long network = base & (0xFFFFFFFFL << (32 - prefix));   // normalize to the network address
        long firstHost, lastHost;
        if (prefix >= 31) {
            firstHost = network;                 // /31, /32: no network/broadcast reserve
            lastHost = network + size - 1;
        } else {
            firstHost = network + 1;             // skip network address
            lastHost = network + size - 2;       // skip broadcast address
        }
        long count = lastHost - firstHost + 1;
        if (count > MAX_HOSTS) {
            throw new IllegalArgumentException(
                    "range too large (" + count + " hosts, max " + MAX_HOSTS + "): " + cidr);
        }
        List<String> out = new ArrayList<>((int) count);
        for (long a = firstHost; a <= lastHost; a++) out.add(longToIpv4(a));
        return out;
    }

    /** Package-private: also used by {@link DiscoveryScanner} to sort results numerically. */
    static long ipv4ToLong(String ip) {
        String[] o = ip.trim().split("\\.");
        if (o.length != 4) throw new IllegalArgumentException("not an IPv4 address: " + ip);
        long v = 0;
        for (String part : o) {
            int b;
            try {
                b = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an IPv4 address: " + ip);
            }
            if (b < 0 || b > 255) throw new IllegalArgumentException("not an IPv4 address: " + ip);
            v = (v << 8) | b;
        }
        return v;
    }

    private static String longToIpv4(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
