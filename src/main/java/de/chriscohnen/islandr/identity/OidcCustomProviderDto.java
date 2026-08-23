package de.chriscohnen.islandr.identity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;

/**
 * DTOs for the generic-OIDC-provider admin API (issue #69). {@link CreateRequest}
 * takes either a {@code preset} ({@code "auth0"}/{@code "okta"}) + {@code domain}
 * — the issuer URL is templated server-side — or a fully generic
 * {@code issuerUrl} when {@code preset} is null.
 */
public final class OidcCustomProviderDto {

    private OidcCustomProviderDto() {}

    @RegisterForReflection
    public record CreateRequest(
            String preset,          // "auth0" | "okta" | null
            String domain,          // required when preset is set
            String issuerUrl,       // required when preset is null
            @NotBlank String displayName,
            String clientId,
            String clientSecret,
            String allowedDomains
    ) {}

    @RegisterForReflection
    public record UpdateRequest(
            String displayName,
            String issuerUrl,       // non-null + different from stored → triggers rediscovery
            String clientId,
            String clientSecret,
            String allowedDomains,
            Boolean enabled
    ) {}

    /** Never carries {@code clientSecret} — same posture as {@link OidcProviderDto}. */
    @RegisterForReflection
    public record Response(
            String id, String preset, String displayName, String issuerUrl,
            boolean discovered, boolean enabled, String clientId, boolean clientSecretSet,
            String allowedDomains, String updatedBy
    ) {
        static Response from(OidcCustomProvider p) {
            return new Response(p.id, p.preset, p.displayName, p.issuerUrl,
                    p.isDiscovered(), p.enabled, p.clientId,
                    p.clientSecret != null && !p.clientSecret.isBlank(),
                    p.allowedDomains, p.updatedBy);
        }
    }
}
