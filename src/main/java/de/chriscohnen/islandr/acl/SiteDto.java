package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.validation.ValidCidr;
import jakarta.validation.constraints.NotBlank;

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
            Boolean outsideSplitSupernet
    ) {
        public static Response from(Site s, int resourceCount,
                                    String gatewayPeerName, Boolean gatewayOnline,
                                    Boolean outsideSplitSupernet) {
            return new Response(s.id, s.name, s.cidr, s.description,
                    resourceCount, s.createdAt, s.updatedAt,
                    s.gatewayPeerId, gatewayPeerName, gatewayOnline, outsideSplitSupernet);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            @NotBlank @ValidCidr
            String cidr,
            String description,
            // optional — peer id of the site gateway router
            String gatewayPeerId
    ) {}

    private SiteDto() {}
}
