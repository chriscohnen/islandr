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
            Double lat,
            Double lng,
            String locationLabel,
            int resourceCount,
            Instant createdAt,
            String gatewayPeerId,
            String gatewayPeerName,
            // null when no gateway configured; true/false based on lastSeenAt within 5 min
            Boolean gatewayOnline
    ) {
        public static Response from(Site s, int resourceCount,
                                    String gatewayPeerName, Boolean gatewayOnline) {
            return new Response(s.id, s.name, s.cidr, s.description, s.lat, s.lng,
                    s.locationLabel, resourceCount, s.createdAt,
                    s.gatewayPeerId, gatewayPeerName, gatewayOnline);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            @NotBlank @ValidCidr
            String cidr,
            String description,
            Double lat,
            Double lng,
            String locationLabel,
            // optional — peer id of the site gateway router
            String gatewayPeerId
    ) {}

    private SiteDto() {}
}
