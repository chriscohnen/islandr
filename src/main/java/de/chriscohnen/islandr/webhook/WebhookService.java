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

        Webhook w = new Webhook();
        w.id = UUID.randomUUID().toString();
        w.url = req.url().trim();
        w.description = blankToNull(req.description());
        String secret = generateSecret();
        w.secret = secret;
        w.eventTypes = Webhook.toCsv(types);
        w.enabled = true;
        w.createdAt = Instant.now();
        w.updatedAt = w.createdAt;
        w.updatedBy = actor;
        w.persist();
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
        if (req.enabled() != null) w.enabled = req.enabled();
        w.updatedAt = Instant.now();
        w.updatedBy = actor;
        return w;
    }

    @Transactional
    public String rotateSecret(String id, String actor) {
        Webhook w = get(id);
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
