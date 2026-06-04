package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class OidcProviderDto {

    /**
     * client_secret is never returned to the browser — once written, it's write-only.
     * We do return whether one is configured so the UI can show "secret gesetzt".
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Response(
            String providerKey,
            boolean enabled,
            String clientId,
            boolean clientSecretSet,
            String tenantId,
            String allowedDomains,
            Instant updatedAt,
            String updatedBy
    ) {
        public static Response from(OidcProvider p) {
            return new Response(
                    p.providerKey,
                    p.enabled,
                    p.clientId,
                    p.clientSecret != null && !p.clientSecret.isBlank(),
                    p.tenantId,
                    p.allowedDomains,
                    p.updatedAt,
                    p.updatedBy
            );
        }
    }

    /**
     * Partial update: any field left null means "don't touch". An empty string
     * for clientSecret means "leave existing", not "clear" — clearing the secret
     * would brick the integration; admin must instead disable the provider.
     * To rotate, send the new secret value.
     */
    public record UpdateRequest(
            Boolean enabled,
            String clientId,
            String clientSecret,
            String tenantId,
            // Comma-separated list of email domains. Whitespace tolerated, case-insensitive.
            @Pattern(regexp = "^$|^([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})(\\s*,\\s*[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})*$",
                    message = "allowedDomains must be a comma-separated list of domains (e.g. firma.de,example.com)")
            String allowedDomains
    ) {}

    private OidcProviderDto() {}
}
