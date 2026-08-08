package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.validation.ValidCidr;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class SiteDto {

    public record Response(
            String id,
            String name,
            String cidr,
            String description,
            int resourceCount,
            Instant createdAt,
            Instant updatedAt,
            String gatewayPeerId,
            String gatewayPeerName,
            // null when no gateway configured; true/false based on lastSeenAt within 5 min
            Boolean gatewayOnline,
            // true when tunnelMode=SPLIT, allowedIpsMode=AUTO, a splitSupernet is
            // configured, and this site's CIDR falls outside it — a non-blocking
            // warning (#33): a peer provisioned before this site existed won't route
            // to it until the split-tunnel supernet is widened or the peer re-imports.
            // null when the check doesn't apply (no gateway, not SPLIT+AUTO, or no
            // supernet configured).
            Boolean outsideSplitSupernet,
            // Explicit DNS label (ADR-0023) — null = derived live from `name`.
            String subdomain,
            // Optional local DNS server for the discovery-scan PTR-lookup
            // suggestion (issue #45). Null = system-resolver fallback.
            String dnsServerIp
    ) {
        public static Response from(Site s, int resourceCount,
                                    String gatewayPeerName, Boolean gatewayOnline,
                                    Boolean outsideSplitSupernet) {
            return new Response(s.id, s.name, s.cidr, s.description,
                    resourceCount, s.createdAt, s.updatedAt,
                    s.gatewayPeerId, gatewayPeerName, gatewayOnline, outsideSplitSupernet,
                    s.subdomain, s.dnsServerIp);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            @NotBlank @ValidCidr
            String cidr,
            String description,
            // optional — peer id of the site gateway router
            String gatewayPeerId,
            // optional — explicit DNS label (ADR-0023). Blank/null = keep deriving
            // it live from `name` (DnsResolverService's original behavior).
            @Pattern(regexp = "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
                    message = "must be a lowercase DNS label (letters, digits, hyphens; not starting/ending with a hyphen)")
            String subdomain,
            // Optional — local DNS server for the discovery-scan PTR-lookup
            // suggestion. Blank/null = fall back to the system resolver.
            @de.chriscohnen.islandr.validation.ValidIpAddress
            String dnsServerIp
    ) {}

    private SiteDto() {}
}
