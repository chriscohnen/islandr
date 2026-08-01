package de.chriscohnen.islandr.peer;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * CIDR parser supporting both IPv4 and IPv6.
 * All bitwise operations use {@link BigInteger} so both families share the same code paths.
 */
public final class IpSubnet {

    private final BigInteger networkInt;
    private final BigInteger maskInt;
    private final int prefix;
    private final boolean v6;

    private IpSubnet(BigInteger networkInt, BigInteger maskInt, int prefix, boolean v6) {
        this.networkInt = networkInt;
        this.maskInt = maskInt;
        this.prefix = prefix;
        this.v6 = v6;
    }

    public static IpSubnet parse(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("CIDR must contain '/': " + cidr);
        String host = cidr.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix in CIDR: " + cidr);
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address in CIDR: " + host);
        }
        boolean v6 = addr instanceof Inet6Address;
        int bitLen = v6 ? 128 : 32;
        if (prefix < 0 || prefix > bitLen) {
            throw new IllegalArgumentException("CIDR prefix out of range [0," + bitLen + "]: " + cidr);
        }
        BigInteger addrInt = new BigInteger(1, addr.getAddress());
        // mask: high 'prefix' bits set. Works for prefix=0 (all zeros) and prefix=bitLen (all ones).
        BigInteger allOnes = BigInteger.ONE.shiftLeft(bitLen).subtract(BigInteger.ONE);
        BigInteger maskInt = allOnes.xor(BigInteger.ONE.shiftLeft(bitLen - prefix).subtract(BigInteger.ONE));
        return new IpSubnet(addrInt.and(maskInt), maskInt, prefix, v6);
    }

    public boolean contains(String ipStr) {
        InetAddress ip;
        try {
            ip = InetAddress.getByName(ipStr);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address: " + ipStr);
        }
        if ((ip instanceof Inet6Address) != v6) return false;
        return new BigInteger(1, ip.getAddress()).and(maskInt).equals(networkInt);
    }

    /**
     * True if this subnet and {@code other} share at least one address.
     * Subnets of different address families never overlap.
     */
    public boolean overlaps(IpSubnet other) {
        if (this.v6 != other.v6) return false;
        BigInteger sharedMask = this.maskInt.and(other.maskInt);
        return this.networkInt.and(sharedMask).equals(other.networkInt.and(sharedMask));
    }

    /**
     * True if {@code other} is fully contained within this subnet: same address
     * family, {@code other}'s prefix at least as specific as this one's, and
     * {@code other}'s network falls inside this one's range. Used to check
     * whether a site's CIDR falls inside the admin-declared split-tunnel
     * supernet (issue #33).
     */
    public boolean containsSubnet(IpSubnet other) {
        if (this.v6 != other.v6) return false;
        if (other.prefix < this.prefix) return false;
        return other.networkInt.and(this.maskInt).equals(this.networkInt);
    }

    /** Returns the WireGuard server address (network + 1 convention). */
    public String networkAddress() {
        return intToAddr(networkInt.add(BigInteger.ONE), v6 ? 16 : 4);
    }

    public int prefix() {
        return prefix;
    }

    /** True when this subnet contains IPv6 addresses. */
    public boolean isV6() {
        return v6;
    }

    /**
     * Iterate assignable host IPs in ascending order.
     * <p>Skips: network address (::0) and server address (::1).
     * For IPv4, also skips the broadcast address.
     * Empty for prefix &gt; 30 (IPv4) or prefix &gt; 126 (IPv6).
     */
    public Iterable<String> assignableHostIps() {
        int bitLen = v6 ? 128 : 32;
        int maxUsablePrefix = v6 ? 126 : 30;
        return () -> new Iterator<>() {
            final BigInteger limit = networkInt.add(BigInteger.ONE.shiftLeft(bitLen - prefix));
            // IPv4 broadcast: one before limit; null for IPv6 (no broadcast)
            final BigInteger broadcast = (!v6 && prefix <= maxUsablePrefix)
                    ? limit.subtract(BigInteger.ONE) : null;
            BigInteger next = networkInt.add(BigInteger.TWO); // skip ::0 and ::1

            @Override
            public boolean hasNext() {
                if (prefix > maxUsablePrefix) return false;
                if (broadcast != null && next.compareTo(broadcast) >= 0) return false;
                return next.compareTo(limit) < 0;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                String ip = intToAddr(next, bitLen / 8);
                next = next.add(BigInteger.ONE);
                return ip;
            }
        };
    }

    private static String intToAddr(BigInteger val, int byteLen) {
        byte[] raw = val.toByteArray();
        byte[] padded = new byte[byteLen];
        // BigInteger may have fewer bytes (leading zeros) or one extra sign byte
        int srcOff = Math.max(0, raw.length - byteLen);
        int dstOff = Math.max(0, byteLen - raw.length);
        System.arraycopy(raw, srcOff, padded, dstOff, Math.min(raw.length, byteLen));
        return byteLen == 16 ? toRfc5952(padded) : dotted(padded);
    }

    // Java's Inet6Address.getHostAddress() does not produce :: compression in JDK 21 —
    // implement RFC 5952 §4 directly to get the canonical compressed form.
    private static String toRfc5952(byte[] b) {
        int[] g = new int[8];
        for (int i = 0; i < 8; i++) g[i] = ((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff);
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (g[i] == 0) {
                int j = i;
                while (j < 8 && g[j] == 0) j++;
                int len = j - i;
                if (len > bestLen) { bestLen = len; bestStart = i; }
                i = j;
            } else i++;
        }
        if (bestLen < 2) bestStart = -1; // only compress runs of two or more groups
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; ) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen;
            } else {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') sb.append(':');
                sb.append(Integer.toHexString(g[i]));
                i++;
            }
        }
        return sb.toString();
    }

    private static String dotted(byte[] b) {
        return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
    }
}
