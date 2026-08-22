package de.chriscohnen.islandr.webhook;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class WebhookDto {

    private WebhookDto() {}

    @RegisterForReflection
    public record CreateRequest(@NotBlank String url, String description, List<String> eventTypes) {}

    @RegisterForReflection
    public record UpdateRequest(String url, String description, List<String> eventTypes, Boolean enabled) {}

    /** Never carries {@code secret} — same one-time-secret posture as a peer's
     *  private key. Only {@link CreateResponse}/{@link SecretResponse} do. */
    @RegisterForReflection
    public record Response(
            String id, String url, String description, List<String> eventTypes, boolean enabled,
            Instant lastDeliveryAt, String lastDeliveryStatus, String lastDeliveryError, String updatedBy
    ) {
        static Response from(Webhook w) {
            return new Response(w.id, w.url, w.description, w.eventTypeSet().stream().toList(), w.enabled,
                    w.lastDeliveryAt, w.lastDeliveryStatus, w.lastDeliveryError, w.updatedBy);
        }
    }

    /** Creation response — carries the plaintext secret exactly once. */
    @RegisterForReflection
    public record CreateResponse(Response webhook, String secret) {}

    /** Response of the explicit "rotate secret" action — same one-time reveal. */
    @RegisterForReflection
    public record SecretResponse(String secret) {}

    @RegisterForReflection
    public record TestFireResponse(boolean success, Integer status, String error) {}
}
