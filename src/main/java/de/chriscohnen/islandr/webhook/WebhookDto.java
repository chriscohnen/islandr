package de.chriscohnen.islandr.webhook;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class WebhookDto {

    private WebhookDto() {}

    /** {@code secret} is only ever admin-supplied for {@code format="gotify"}
     *  — the Gotify app token, generated in Gotify's own UI (Apps → Create
     *  App) and pasted in here. For {@code format="generic"} it's ignored;
     *  the HMAC secret is always server-generated. */
    @RegisterForReflection
    public record CreateRequest(@NotBlank String url, String description, List<String> eventTypes,
                                String format, String secret) {}

    @RegisterForReflection
    public record UpdateRequest(String url, String description, List<String> eventTypes, Boolean enabled,
                                String format, String secret) {}

    /** Never carries {@code secret} — same one-time-secret posture as a peer's
     *  private key. Only {@link CreateResponse}/{@link SecretResponse} do. */
    @RegisterForReflection
    public record Response(
            String id, String url, String description, List<String> eventTypes, String format, boolean enabled,
            boolean secretSet, Instant lastDeliveryAt, String lastDeliveryStatus, String lastDeliveryError, String updatedBy
    ) {
        static Response from(Webhook w) {
            return new Response(w.id, w.url, w.description, w.eventTypeSet().stream().toList(), w.format, w.enabled,
                    w.secret != null && !w.secret.isBlank(),
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
