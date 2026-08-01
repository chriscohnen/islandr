package de.chriscohnen.islandr.peer;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the client-side {@code AllowedIPs} value for a peer's WireGuard
 * config, per the explicit full/split tunnel design (issue #33, ADR-0017,
 * see docs/superpowers/specs/2026-07-29-split-tunnel-allowed-ips-design.md).
 */
public final class AllowedIpsCalculator {

    private AllowedIpsCalculator() {}

    /**
     * @param tunnelMode     "FULL" or "SPLIT"
     * @param allowedIpsMode "AUTO" or "MANUAL"
     * @param manualValue    raw AllowedIPs string used verbatim when allowedIpsMode is MANUAL;
     *                       ignored (may be null) when allowedIpsMode is AUTO
     * @param wgSubnet       VPN IPv4 subnet, e.g. "10.8.0.0/24" (required for SPLIT+AUTO)
     * @param wgSubnet6      VPN IPv6 subnet, e.g. "fd11::/64", or null for IPv4-only
     * @param splitSupernet  admin-declared supernet CIDR sized to cover current and future
     *                       site networks, or null/blank if not configured (SPLIT+AUTO only)
     * @param siteCidrs      CIDR of every site with an enabled gateway peer, regardless of
     *                       whether it falls inside {@code splitSupernet}. A site fully
     *                       contained in {@code splitSupernet} is redundant and omitted; a
     *                       site outside it is not reachable via the supernet route alone and
     *                       is appended individually so it's still routed. With no supernet
     *                       configured, every site CIDR is listed (nothing to omit).
     * @param dnsValue       Settings.wgClientDns raw value — may contain split-DNS syntax
     *                       such as "10.0.0.1, ~example.com" — or null
     * @param includeDns     whether this peer's config writes a DNS line at all
     *                       (Peer.includeDns); the DNS host-route fix-up only matters when
     *                       a DNS line is actually written
     */
    public static String compute(String tunnelMode, String allowedIpsMode, String manualValue,
                                  String wgSubnet, String wgSubnet6, String splitSupernet,
                                  List<String> siteCidrs,
                                  String dnsValue, boolean includeDns) {
        if ("MANUAL".equals(allowedIpsMode)) {
            return manualValue;
        }

        List<String> parts = new ArrayList<>();
        if ("FULL".equals(tunnelMode)) {
            parts.add("0.0.0.0/0");
            if (hasValue(wgSubnet6)) parts.add("::/0");
        } else {
            parts.add(wgSubnet);
            if (hasValue(wgSubnet6)) parts.add(wgSubnet6);
            if (hasValue(splitSupernet)) parts.add(splitSupernet.trim());
            appendSiteCidrsNotCoveredBySupernet(parts, siteCidrs, splitSupernet);
            appendDnsHostRoutesIfNeeded(parts, dnsValue, includeDns);
        }
        return String.join(", ", parts);
    }

    /**
     * A site's CIDR is only redundant to list individually if {@code splitSupernet}
     * fully contains it — otherwise a peer would have no route to it at all despite
     * the supernet entry. Appends every {@code siteCidrs} entry not covered by
     * {@code splitSupernet} (including all of them when no supernet is configured).
     */
    private static void appendSiteCidrsNotCoveredBySupernet(List<String> parts, List<String> siteCidrs,
                                                             String splitSupernet) {
        if (siteCidrs == null || siteCidrs.isEmpty()) return;

        IpSubnet supernet = null;
        if (hasValue(splitSupernet)) {
            try {
                supernet = IpSubnet.parse(splitSupernet.trim());
            } catch (IllegalArgumentException ignored) {
                // malformed supernet — fail safe by treating every site as uncovered
                // (still routed) rather than silently dropping a real route
            }
        }

        for (String siteCidr : siteCidrs) {
            if (!hasValue(siteCidr)) continue;
            if (supernet != null) {
                try {
                    if (supernet.containsSubnet(IpSubnet.parse(siteCidr))) continue; // redundant, already covered
                } catch (IllegalArgumentException ignored) {
                    // malformed site CIDR — fail safe by including it rather than dropping it
                }
            }
            parts.add(siteCidr.trim());
        }
    }

    private static boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * A split-tunnel client can have a {@code DNS =} line pointing at a server
     * it has no route to — resolution then fails silently. Appends a /32 (or
     * /128 for IPv6) host route for each DNS IP in {@code dnsValue} that isn't
     * already covered by {@code parts}. Split-DNS domain suffixes
     * ("~example.com") are not IP literals and are skipped.
     */
    private static void appendDnsHostRoutesIfNeeded(List<String> parts, String dnsValue, boolean includeDns) {
        if (!includeDns || !hasValue(dnsValue)) return;

        List<IpSubnet> routed = new ArrayList<>();
        for (String cidr : parts) {
            try {
                routed.add(IpSubnet.parse(cidr));
            } catch (IllegalArgumentException ignored) {
                // malformed entry in parts — nothing to route DNS through here
            }
        }

        for (String token : dnsValue.split(",")) {
            String candidate = token.trim();
            if (candidate.isEmpty() || candidate.startsWith("~")) continue; // split-DNS domain suffix, not an IP

            boolean isIpv4 = candidate.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
            boolean isIpv6 = candidate.contains(":");
            if (!isIpv4 && !isIpv6) continue; // not an IP literal — skip

            boolean covered = false;
            for (IpSubnet subnet : routed) {
                try {
                    if (subnet.contains(candidate)) {
                        covered = true;
                        break;
                    }
                } catch (IllegalArgumentException ignored) {
                    // candidate isn't a valid address for this family — not covered by it
                }
            }
            if (!covered) {
                parts.add(candidate + (isIpv6 ? "/128" : "/32"));
            }
        }
    }
}
