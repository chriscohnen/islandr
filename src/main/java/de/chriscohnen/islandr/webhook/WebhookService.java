package de.chriscohnen.islandr.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** CRUD for admin-configured webhooks (issue #68). Delivery itself lives in
 *  {@link WebhookDispatcher} — this class is config-only. */
@ApplicationScoped
public class WebhookService {

    private volatile SecureRandom rng;

    public List<Webhook> listAll() {
        return Webhook.<Webhook>listAll();
    }

    public Webhook get(String id) {
        Webhook w = Webhook.findById(id);
        if (w == null) throw new NotFoundException("unknown webhook: " + id);
        return w;
    }

    public record CreateResult(Webhook webhook, String plaintextSecret) {}

    @Transactional
    public CreateResult create(WebhookDto.CreateRequest req, String actor) {
        validateUrl(req.url());
        List<String> types = validatedEventTypes(req.eventTypes());
        String format = validatedFormat(req.format());

        Webhook w = new Webhook();
        w.id = UUID.randomUUID().toString();
        w.url = req.url().trim();
        w.description = blankToNull(req.description());
        w.format = format;
        String secret;
        if (WebhookFormat.GOTIFY.equals(format)) {
            // The Gotify app token is admin-obtained (Gotify UI → Apps →
            // Create App), not something we can generate ourselves.
            if (req.secret() == null || req.secret().isBlank()) {
                throw new BadRequestException("secret (the Gotify app token) is required for format=gotify");
            }
            secret = req.secret().trim();
        } else {
            secret = generateSecret();
        }
        w.secret = secret;
        w.eventTypes = Webhook.toCsv(types);
        w.enabled = true;
        w.createdAt = Instant.now();
        w.updatedAt = w.createdAt;
        w.updatedBy = actor;
        w.persist();
        // Gotify's token is the admin's own value — reflecting it back isn't
        // "revealing a generated secret" (there's nothing to lose), but the
        // API stays uniform: only the generic (server-generated) case truly
        // needs the one-time-reveal treatment on the frontend.
        return new CreateResult(w, secret);
    }

    @Transactional
    public Webhook update(String id, WebhookDto.UpdateRequest req, String actor) {
        Webhook w = get(id);
        if (req.url() != null && !req.url().isBlank()) {
            validateUrl(req.url());
            w.url = req.url().trim();
        }
        if (req.description() != null) w.description = blankToNull(req.description());
        if (req.eventTypes() != null) w.eventTypes = Webhook.toCsv(validatedEventTypes(req.eventTypes()));
        if (req.format() != null) w.format = validatedFormat(req.format());
        // Blank secret = "no change" (same convention as OidcProvider.clientSecret) —
        // only meaningful for gotify, where it's an admin-re-enterable app
        // token, not an auto-rotated HMAC key.
        if (req.secret() != null && !req.secret().isBlank()) w.secret = req.secret().trim();
        if (req.enabled() != null) w.enabled = req.enabled();
        w.updatedAt = Instant.now();
        w.updatedBy = actor;
        return w;
    }

    /** Only meaningful for {@link WebhookFormat#GENERIC} — a Gotify app token
     *  is admin-obtained, not something we can regenerate; changing it goes
     *  through {@link #update} with a new {@code secret} instead. */
    @Transactional
    public String rotateSecret(String id, String actor) {
        Webhook w = get(id);
        if (WebhookFormat.GOTIFY.equals(w.format)) {
            throw new BadRequestException("cannot auto-rotate a Gotify app token — update it with the new token from Gotify instead");
        }
        String secret = generateSecret();
        w.secret = secret;
        w.updatedAt = Instant.now();
        w.updatedBy = actor;
        return secret;
    }

    @Transactional
    public void delete(String id) {
        get(id).delete();
    }

    private static void validateUrl(String url) {
        if (url == null || url.isBlank()) throw new BadRequestException("url is required");
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BadRequestException("url must start with http:// or https://");
        }
    }

    private static String validatedFormat(String format) {
        if (format == null || format.isBlank()) return WebhookFormat.GENERIC;
        if (!WebhookFormat.ALL.contains(format)) throw new BadRequestException("unknown format: " + format);
        return format;
    }

    private static List<String> validatedEventTypes(List<String> types) {
        if (types == null || types.isEmpty()) return List.of();
        for (String t : types) {
            if (!WebhookEventType.ALL.contains(t)) {
                throw new BadRequestException("unknown event type: " + t);
            }
        }
        return types;
    }

    private String generateSecret() {
        byte[] buf = new byte[32];
        rng().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private SecureRandom rng() {
        SecureRandom r = rng;
        if (r == null) {
            synchronized (this) {
                r = rng;
                if (r == null) {
                    r = new SecureRandom();
                    rng = r;
                }
            }
        }
        return r;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
