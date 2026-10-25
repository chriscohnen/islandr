package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.peer.IpSubnet;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the hub may fetch a URL that came from outside — today the
 * OIDC {@code picture} claim (oidc-avatar-ssrf-xss).
 *
 * <p>That claim is whatever the identity provider puts there, and on a
 * self-run provider whatever the user puts there. Fetched unchecked, it made
 * the hub request any address it can reach — loopback, its own WireGuard
 * subnet, the site networks it routes into — and hand the answer back as the
 * user's avatar. So:
 *
 * <ul>
 *   <li>{@code https} only;</li>
 *   <li>the host is resolved and <b>every</b> address checked, because the
 *       client may connect to any of them;</li>
 *   <li>loopback, unspecified, private, link-local, CGNAT, unique-local,
 *       multicast and reserved ranges are refused, and so is anything inside
 *       a network the hub routes (WireGuard subnet, site networks) — those can
 *       be public ranges, reachable from the hub and from nowhere else.</li>
 * </ul>
 *
 * <p>Redirects are the caller's job: each hop goes through this check again.
 *
 * <p>Residual: DNS rebinding between this check and the connection. The JVM
 * caches a positive lookup for 30 s and the HTTP client resolves through the
 * same cache, so a rebind would have to land in that gap; accepted for an
 * avatar fetch.
 */
public final class UrlFetchPolicy {

    /** Why a URL may not be fetched. The message is for the log, never for a page. */
    public static final class Refused extends RuntimeException {
        Refused(String message) { super(message); }
    }

    private static final List<IpSubnet> NEVER = parseAll(List.of(
            "0.0.0.0/8",        // "this network"
            "100.64.0.0/10",    // carrier-grade NAT
            "192.0.0.0/24",     // IETF protocol assignments
            "198.18.0.0/15",    // benchmarking
            "240.0.0.0/4",      // reserved, incl. broadcast
            "fc00::/7",         // unique local
            "64:ff9b::/96",     // NAT64 — would reach any IPv4 behind it
            "2002::/16"         // 6to4 — embeds an arbitrary IPv4
    ));

    private UrlFetchPolicy() {}

    /**
     * @param routedCidrs networks the hub routes into; malformed entries are
     *        ignored (they cannot widen anything)
     * @return the parsed URI, when it may be fetched
     * @throws Refused naming the reason, for the log
     */
    public static URI requireFetchable(String url, HostResolver resolver, List<String> routedCidrs) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            throw new Refused("not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) throw new Refused("only https is fetched, not '" + scheme + "'");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new Refused("no host");
        if (uri.getRawUserInfo() != null) throw new Refused("credentials in the URL");

        InetAddress[] addresses;
        try {
            // An address literal is checked as itself, never handed to the
            // resolver: what a literal means does not depend on DNS, and a
            // resolver swapped in for tests or misbehaving must not be able
            // to turn 127.0.0.1 into something else.
            addresses = isAddressLiteral(host)
                    ? new InetAddress[] { InetAddress.getByName(host) }
                    : resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new Refused("host does not resolve");
        }
        if (addresses == null || addresses.length == 0) throw new Refused("host does not resolve");

        List<IpSubnet> routed = parseAll(routedCidrs);
        for (InetAddress a : addresses) {
            String why = refusedBecause(a, routed);
            if (why != null) throw new Refused(host + " resolves to " + a.getHostAddress() + " (" + why + ")");
        }
        return uri;
    }

    /** URI.getHost() keeps IPv6 literals in brackets; IPv4 literals are four
     *  dotted decimals. getByName() parses either without a lookup. */
    private static boolean isAddressLiteral(String host) {
        return host.startsWith("[") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    static String refusedBecause(InetAddress a, List<IpSubnet> routed) {
        if (a.isAnyLocalAddress()) return "unspecified";
        if (a.isLoopbackAddress()) return "loopback";
        if (a.isLinkLocalAddress()) return "link-local";
        if (a.isSiteLocalAddress()) return "private";
        if (a.isMulticastAddress()) return "multicast";
        if (a instanceof Inet6Address v6 && v6.isIPv4CompatibleAddress()) return "IPv4-compatible";
        String ip = a.getHostAddress();
        for (IpSubnet s : NEVER) if (sameFamily(s, a) && s.contains(ip)) return "reserved";
        for (IpSubnet s : routed) if (sameFamily(s, a) && s.contains(ip)) return "a network the hub routes";
        return null;
    }

    /** IpSubnet compares within one family; an IPv4-mapped IPv6 literal
     *  already arrives as an Inet4Address from the JDK. */
    private static boolean sameFamily(IpSubnet s, InetAddress a) {
        return s.isV6() == (a instanceof Inet6Address);
    }

    private static List<IpSubnet> parseAll(List<String> cidrs) {
        List<IpSubnet> out = new ArrayList<>();
        if (cidrs == null) return out;
        for (String c : cidrs) {
            if (c == null || c.isBlank()) continue;
            try {
                out.add(IpSubnet.parse(c.trim()));
            } catch (RuntimeException ignored) {
                // A malformed entry cannot open anything; skipping it only
                // means it does not close anything either.
            }
        }
        return out;
    }
}
