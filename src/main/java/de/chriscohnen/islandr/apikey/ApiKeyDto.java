package de.chriscohnen.islandr.apikey;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class ApiKeyDto {

    private ApiKeyDto() {}

    /** @param scopes must be a non-empty subset of {@link ApiKeyScope#CATALOG}
     *         (apikey-scopes) — validated in {@link ApiKeyService#create},
     *         not here, since a fixed-catalog membership check isn't a bean
     *         validation annotation this codebase otherwise uses. */
    @RegisterForReflection
    public record CreateRequest(@NotBlank String label, @NotEmpty Set<String> scopes) {}

    /** Never carries {@link #keyHash} equivalents — only what's safe to list
     *  after creation. */
    @RegisterForReflection
    public record Response(
            String id, String label, String keyPrefix, Instant createdAt, String createdBy,
            Instant lastUsedAt, boolean revoked, List<String> scopes
    ) {
        static Response from(ApiKey k) {
            return new Response(k.id, k.label, k.keyPrefix, k.createdAt, k.createdBy,
                    k.lastUsedAt, !k.isActive(), ApiKeyScope.scopesOf(k.id));
        }
    }

    /** Creation response — carries the plaintext key exactly once. */
    @RegisterForReflection
    public record CreateResponse(Response apiKey, String rawKey) {}
}
