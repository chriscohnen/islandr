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
            int resourceCount,
            Instant createdAt
    ) {
        public static Response from(Site s, int resourceCount) {
            return new Response(s.id, s.name, s.cidr, s.description, resourceCount, s.createdAt);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            @NotBlank
            @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}$",
                    message = "must be IPv4 CIDR (e.g. 10.20.0.0/16)")
            String cidr,
            String description
    ) {}

    private SiteDto() {}
}
