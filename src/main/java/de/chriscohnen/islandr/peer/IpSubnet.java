package de.chriscohnen.islandr.peer;

/**
 * Tiny IPv4 CIDR parser. The JDK has no built-in for "is this IP inside this CIDR"
 * — pulling Guava or commons-net for one method is overkill. IPv6 deferred to v2.
 */
public final class IpSubnet {

    private final int networkInt;
    private final int maskInt;
    private final int prefix;

    private IpSubnet(int networkInt, int maskInt, int prefix) {
        this.networkInt = networkInt;
        this.maskInt = maskInt;
        this.prefix = prefix;
    }

    public static IpSubnet parse(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("CIDR must contain '/': " + cidr);
        }
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("CIDR prefix out of range: " + cidr);
        }
        int ip = ipv4ToInt(cidr.substring(0, slash));
        int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        return new IpSubnet(ip & mask, mask, prefix);
    }

    public boolean contains(String ipv4) {
        int ip = ipv4ToInt(ipv4);
        return (ip & maskInt) == networkInt;
    }

    /**
     * True if this subnet and {@code other} share any address. Either is a
     * superset of the other, or they're literally equal. Used to keep site-peer
     * CIDR declarations from clashing with the WG subnet or with each other.
     */
    public boolean overlaps(IpSubnet other) {
        int sharedMask = this.maskInt & other.maskInt;
        return (this.networkInt & sharedMask) == (other.networkInt & sharedMask);
    }

    /** Network address (first IP) of this subnet — typically the wg server IP. */
    public String networkAddress() {
        return intToIpv4(networkInt | 1);
    }

    public int prefix() {
        return prefix;
    }

    /**
     * Iterate assignable host IPs inside this subnet, in ascending order.
     *
     * <p>Skips:
     * <ul>
     *   <li>{@code .0} — network address (or for prefixes &gt; 30, the single block address)</li>
     *   <li>{@code .255} (or analog) — broadcast for prefixes &lt;= 30</li>
     *   <li>{@code .1} — convention for the WireGuard server interface itself</li>
     * </ul>
     *
     * <p>For odd prefixes ({@code /31}, {@code /32}) the iterable is empty —
     * there's nothing useful to allocate inside a single-host or two-host block
     * once the server takes one slot.
     */
    public Iterable<String> assignableHostIps() {
        return () -> new java.util.Iterator<>() {
            // long avoids sign-flip surprises around the 0.0.0.0/0 edges.
            final long network = Integer.toUnsignedLong(networkInt);
            final long size = prefix >= 32 ? 1L : 1L << (32 - prefix);
            final long broadcast = prefix <= 30 ? network + size - 1 : -1L;
            // Skip .0 and .1 (server). For /30 there are 4 addresses; .2 is the
            // only legitimate peer slot. For /31, /32 nothing is assignable.
            long next = prefix <= 30 ? network + 2 : network + size;

            @Override
            public boolean hasNext() {
                return prefix <= 30 && next < broadcast;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                String ip = intToIpv4((int) next);
                next++;
                return ip;
            }
        };
    }

    public static int ipv4ToInt(String ipv4) {
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 address: " + ipv4);
        }
        int result = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("octet out of range in: " + ipv4);
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    public static String intToIpv4(int v) {
        return ((v >>> 24) & 0xFF) + "." + ((v >>> 16) & 0xFF) + "." + ((v >>> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
