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

    /**
     * One network a site-gateway peer already routes but that Islandr does not
     * know as a Site yet. Returned by {@code GET /api/v1/sites/gateway-import-preview}.
     *
     * <p>The gateway's AllowedIPs are the authoritative list of what is reachable
     * behind it; re-typing them as Sites by hand is transcription work with a
     * typo budget, and a Site whose CIDR does not match what the gateway routes
     * grants access to nothing.
     */
    public record GatewayNetworkCandidate(
            String peerId,
            String peerName,
            String cidr,
            String suggestedName,
            // A Site with this CIDR already exists — its name, so the dialog can
            // say which one rather than just greying the row out.
            String existingSiteName
    ) {}

    /** One entry in a {@code POST /api/v1/sites/gateway-import} request. */
    public record GatewayNetworkEntry(
            @NotBlank String peerId,
            @NotBlank @ValidCidr String cidr,
            @NotBlank String name,
            String description
    ) {}

    /** Request body for {@code POST /api/v1/sites/gateway-import}. */
    public record GatewayImportRequest(
            @jakarta.validation.Valid
            @jakarta.validation.constraints.NotNull
            java.util.List<GatewayNetworkEntry> networks
    ) {}

    /** Outcome per entry: "imported" | "skipped" (CIDR already a Site). */
    public record GatewayImportResult(String cidr, String status, String siteId) {}

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
