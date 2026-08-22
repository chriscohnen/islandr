package de.chriscohnen.islandr.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The only place {@link WebhookDispatcher} touches the database — isolated
 * here because delivery runs on a plain background thread pool
 * ({@code WebhookDispatcher}'s own executor), which has no CDI request/
 * transaction context by default. {@code @ActivateRequestContext} is
 * Quarkus's documented way to get one on an arbitrary thread for the
 * duration of a single call: every method here activates its own context,
 * does its DB work, and lets it go — the slow part (the actual HTTP POST +
 * retry/backoff sleep) deliberately happens in {@link WebhookDispatcher}
 * OUTSIDE any of these calls, so no DB connection sits open across a
 * network round trip.
 */
@ApplicationScoped
public class WebhookDeliveryRecorder {

    public record Snapshot(String id, String url, String secret, String format) {}

    @ActivateRequestContext
    public List<Snapshot> findSubscribed(String eventType) {
        return Webhook.<Webhook>list("enabled = true").stream()
                .filter(w -> w.isSubscribed(eventType))
                .map(w -> new Snapshot(w.id, w.url, w.secret, w.format))
                .toList();
    }

    @ActivateRequestContext
    public Snapshot find(String id) {
        Webhook w = Webhook.findById(id);
        return w == null ? null : new Snapshot(w.id, w.url, w.secret, w.format);
    }

    @ActivateRequestContext
    @Transactional
    public void recordDelivery(String id, boolean success, String error) {
        Webhook w = Webhook.findById(id);
        if (w == null) return; // deleted between dispatch and completion — nothing to record
        w.lastDeliveryAt = Instant.now();
        w.lastDeliveryStatus = success ? "ok" : "failed";
        w.lastDeliveryError = success ? null : error;
    }
}
