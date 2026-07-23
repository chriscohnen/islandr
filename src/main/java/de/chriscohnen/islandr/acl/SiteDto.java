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
            Boolean gatewayOnline
    ) {
        public static Response from(Site s, int resourceCount,
                                    String gatewayPeerName, Boolean gatewayOnline) {
            return new Response(s.id, s.name, s.cidr, s.description,
                    resourceCount, s.createdAt, s.updatedAt,
                    s.gatewayPeerId, gatewayPeerName, gatewayOnline);
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
