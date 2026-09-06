package de.chriscohnen.islandr.discovery;

import java.util.List;

/**
 * Which name and MAC sources a scan of a given network can actually draw on
 * (issue #79).
 *
 * <p>Discovery asks several sources per host and each may legitimately come up
 * empty, which leaves an operator unable to tell "this device answered
 * nothing" from "we never asked". The difference is knowable before the first
 * probe: it follows from the site's own configuration and the hub's situation,
 * not from any individual host. So the scan dialog states it up front rather
 * than letting a half-empty result table imply a broken feature.
 *
 * <p>Pure derivation — nothing here touches the network. It mirrors the gates
 * {@link HostProbe} applies per host; keep the two in step.
 */
final class ScanSources {

    private ScanSources() {}

    private static final String OFF_LINK = "off_link";
    private static final String NO_SITE_DNS = "no_site_dns";
    private static final String NO_ARP_TABLE = "no_arp_table";
    private static final String MOCK_MODE = "mock_mode";

    /**
     * @param siteCidr        the network about to be scanned
     * @param siteDnsServerIp the site's own DNS server, if one is configured (issue #45)
     * @param realMode        false when {@code islandr.discovery.mode} is not {@code real}
     */
    static List<DiscoveryDto.NameSource> forSite(String siteCidr, String siteDnsServerIp, boolean realMode,
                                                 LinkScope linkScope, ArpCache arpCache) {
        if (!realMode) {
            // Nothing is asked of the network at all, so listing per-source
            // reasons would only describe a chain that does not run.
            return List.of("ptr_site", "ptr_system", "mdns", "llmnr", "netbios", "ssdp", "arp").stream()
                    .map(id -> new DiscoveryDto.NameSource(id, false, MOCK_MODE))
                    .toList();
        }

        boolean onLink = linkScope.overlaps(siteCidr);
        boolean hasSiteDns = siteDnsServerIp != null && !siteDnsServerIp.isBlank();
        String linkScopedReason = onLink ? null : OFF_LINK;

        // Listed in the order HostProbe asks, so the display doubles as an
        // accurate description of the resolution chain.
        return List.of(
                source("ptr_site", hasSiteDns, NO_SITE_DNS),
                source("ptr_system", true, null),
                source("mdns", onLink, linkScopedReason),
                source("llmnr", onLink, linkScopedReason),
                source("netbios", true, null),
                source("ssdp", true, null),
                // Two independent reasons ARP can be unavailable, and they mean
                // different things to an operator: a remote network is expected,
                // a missing /proc/net/arp is the platform.
                source("arp", onLink && arpCache.available(), !onLink ? OFF_LINK : NO_ARP_TABLE));
    }

    private static DiscoveryDto.NameSource source(String id, boolean active, String reasonIfInactive) {
        return new DiscoveryDto.NameSource(id, active, active ? null : reasonIfInactive);
    }
}
