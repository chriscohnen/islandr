package de.chriscohnen.islandr.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.identity.HttpFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fires outgoing webhook deliveries (issue #68). In-process only — no
 * external message broker, matching the rest of Islandr's background jobs
 * (Peer-Scheduler, ActivityPoller, discovery scans). A bounded thread pool
 * keeps a slow/dead receiving endpoint from ever blocking the event source
 * (nftables/wg enforcement, ACL apply, etc. stay completely decoupled).
 *
 * <p>Delivery: up to 3 attempts with exponential backoff (2s, 8s) — a
 * webhook receiver that's down for a minute still gets the event once it
 * comes back, without Islandr running its own message queue.
 *
 * <p>Security: every delivery is HMAC-SHA256-signed over the raw JSON body
 * with the webhook's own secret, in an {@code X-Islandr-Signature} header
 * ({@code sha256=<hex>}) — the receiving end can verify the payload actually
 * came from this Islandr instance and wasn't tampered with in transit.
 */
@ApplicationScoped
public class WebhookDispatcher {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long[] BACKOFF_MILLIS = { 2_000, 8_000 };

    @Inject HttpFetcher http;
    @Inject WebhookDeliveryRecorder recorder;

    // Bounded — a burst of events (e.g. a big ACL matrix apply) queues rather
    // than spawning unbounded threads; deliveries are best-effort notifications,
    // not something that needs to race ahead of the event source.
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "webhook-delivery");
        t.setDaemon(true);
        return t;
    });

    /**
     * Fire-and-forget: looks up every enabled webhook subscribed to
     * {@code eventType} and queues a delivery for each. Returns immediately —
     * callers (ACL apply, Peer-Scheduler, ActivityPoller, ...) never wait on
     * network I/O to a third party they don't control.
     */
    public void publish(String eventType, String actor, String target, Map<String, Object> data) {
        List<WebhookDeliveryRecorder.Snapshot> subscribers = recorder.findSubscribed(eventType);
        for (WebhookDeliveryRecorder.Snapshot w : subscribers) {
            byte[] payload = buildPayload(eventType, actor, target, data);
            pool.submit(() -> deliverWithRetry(w, payload));
        }
    }

    /** The admin UI's "test connection" action — a single synchronous
     *  attempt (no retry, no backoff) so the admin gets an immediate result,
     *  fired regardless of the webhook's own event-type filter. */
    public WebhookDto.TestFireResponse testFire(String webhookId) {
        WebhookDeliveryRecorder.Snapshot w = recorder.find(webhookId);
        if (w == null) return new WebhookDto.TestFireResponse(false, null, "webhook not found");
        byte[] payload = buildPayload(WebhookEventType.TEST, "test", "Webhook:" + w.id(),
                Map.of("message", "This is a test delivery from Islandr."));
        Attempt result = attempt(w, payload);
        recorder.recordDelivery(w.id(), result.success(), result.error());
        return new WebhookDto.TestFireResponse(result.success(), result.status(), result.error());
    }

    private void deliverWithRetry(WebhookDeliveryRecorder.Snapshot w, byte[] payload) {
        Attempt last = null;
        for (int i = 0; i <= BACKOFF_MILLIS.length; i++) {
            last = attempt(w, payload);
            if (last.success()) break;
            if (i < BACKOFF_MILLIS.length) sleep(BACKOFF_MILLIS[i]);
        }
        recorder.recordDelivery(w.id(), last.success(), last.error());
    }

    private record Attempt(boolean success, Integer status, String error) {}

    private Attempt attempt(WebhookDeliveryRecorder.Snapshot w, byte[] payload) {
        try {
            String signature = hmacSha256Hex(w.secret(), payload);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Islandr-Signature", "sha256=" + signature);
            headers.put("X-Islandr-Delivery", UUID.randomUUID().toString());
            HttpFetcher.Response r = http.postBody(w.url(), payload, "application/json", headers);
            if (r.status() >= 200 && r.status() < 300) {
                return new Attempt(true, r.status(), null);
            }
            return new Attempt(false, r.status(), "HTTP " + r.status());
        } catch (Exception ex) {
            return new Attempt(false, null, ex.getMessage());
        }
    }

    private static byte[] buildPayload(String eventType, String actor, String target, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", eventType);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("actor", actor);
        envelope.put("target", target);
        envelope.put("data", data == null ? Map.of() : data);
        try {
            return JSON.writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("could not serialize webhook payload", ex);
        }
    }

    private static String hmacSha256Hex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(body);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
