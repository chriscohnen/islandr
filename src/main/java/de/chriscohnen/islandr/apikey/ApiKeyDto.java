package de.chriscohnen.islandr.apikey;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class ApiKeyDto {

    private ApiKeyDto() {}

    @RegisterForReflection
    public record CreateRequest(@NotBlank String label) {}

    /** Never carries {@link #keyHash} equivalents — only what's safe to list
     *  after creation. */
    @RegisterForReflection
    public record Response(
            String id, String label, String keyPrefix, Instant createdAt, String createdBy,
            Instant lastUsedAt, boolean revoked
    ) {
        static Response from(ApiKey k) {
            return new Response(k.id, k.label, k.keyPrefix, k.createdAt, k.createdBy,
                    k.lastUsedAt, !k.isActive());
        }
    }

    /** Creation response — carries the plaintext key exactly once. */
    @RegisterForReflection
    public record CreateResponse(Response apiKey, String rawKey) {}
}
