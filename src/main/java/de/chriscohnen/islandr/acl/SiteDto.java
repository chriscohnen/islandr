package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class SiteDto {

    public record Response(
            String id,
            String name,
            String cidr,
            String description,
            Double lat,
            Double lng,
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
                    resourceCount, s.createdAt, s.gatewayPeerId, gatewayPeerName, gatewayOnline);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            @NotBlank
            @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}$",
                    message = "must be IPv4 CIDR (e.g. 10.20.0.0/16)")
            String cidr,
            String description,
            Double lat,
            Double lng,
            // optional — peer id of the site gateway router
            String gatewayPeerId
    ) {}

    private SiteDto() {}
}
