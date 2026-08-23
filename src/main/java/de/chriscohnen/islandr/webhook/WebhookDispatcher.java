package de.chriscohnen.islandr.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.identity.HttpFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
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
 * <p>Payload shape depends on the webhook's {@link WebhookFormat}:
 * {@link WebhookFormat#GENERIC} sends Islandr's own envelope, HMAC-SHA256-
 * signed (an {@code X-Islandr-Signature} header the receiving end can
 * verify); {@link WebhookFormat#GOTIFY} renders Gotify's native push shape
 * instead and posts straight to Gotify's {@code /message} endpoint — Gotify
 * has no HMAC verification story, so no signature header is sent for it.
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
            pool.submit(() -> deliverWithRetry(w, eventType, actor, target, data));
        }
    }

    /** The admin UI's "test connection" action — a single synchronous
     *  attempt (no retry, no backoff) so the admin gets an immediate result,
     *  fired regardless of the webhook's own event-type filter. */
    public WebhookDto.TestFireResponse testFire(String webhookId) {
        WebhookDeliveryRecorder.Snapshot w = recorder.find(webhookId);
        if (w == null) return new WebhookDto.TestFireResponse(false, null, "webhook not found");
        Attempt result = attempt(w, WebhookEventType.TEST, "test", "Webhook:" + w.id(),
                Map.of("message", "This is a test delivery from Islandr."));
        recorder.recordDelivery(w.id(), result.success(), result.error());
        return new WebhookDto.TestFireResponse(result.success(), result.status(), result.error());
    }

    private void deliverWithRetry(WebhookDeliveryRecorder.Snapshot w, String eventType, String actor,
                                  String target, Map<String, Object> data) {
        Attempt last = null;
        for (int i = 0; i <= BACKOFF_MILLIS.length; i++) {
            last = attempt(w, eventType, actor, target, data);
            if (last.success()) break;
            if (i < BACKOFF_MILLIS.length) sleep(BACKOFF_MILLIS[i]);
        }
        recorder.recordDelivery(w.id(), last.success(), last.error());
    }

    private record Attempt(boolean success, Integer status, String error) {}

    private Attempt attempt(WebhookDeliveryRecorder.Snapshot w, String eventType, String actor,
                            String target, Map<String, Object> data) {
        try {
            boolean gotify = WebhookFormat.GOTIFY.equals(w.format());
            String endpoint = gotify ? gotifyEndpoint(w.url(), w.secret()) : w.url();
            byte[] body = gotify
                    ? JSON.writeValueAsBytes(gotifyPayload(eventType, target, data))
                    : JSON.writeValueAsBytes(genericEnvelope(eventType, actor, target, data));

            Map<String, String> headers = new LinkedHashMap<>();
            if (!gotify) {
                // Gotify has no signature-verification story — sending it a
                // header it will never check is just noise.
                headers.put("X-Islandr-Signature", "sha256=" + hmacSha256Hex(w.secret(), body));
                headers.put("X-Islandr-Delivery", UUID.randomUUID().toString());
            }
            // Optional extra auth header (e.g. Authorization/X-API-Key) some
            // receivers require alongside or instead of the HMAC signature —
            // sent regardless of format, since even a Gotify instance might sit
            // behind a reverse proxy gating on its own header.
            if (w.extraHeaderName() != null && !w.extraHeaderName().isBlank()) {
                headers.put(w.extraHeaderName(), w.extraHeaderValue());
            }
            HttpFetcher.Response r = http.postBody(endpoint, body, "application/json", headers);
            if (r.status() >= 200 && r.status() < 300) {
                return new Attempt(true, r.status(), null);
            }
            return new Attempt(false, r.status(), "HTTP " + r.status());
        } catch (Exception ex) {
            return new Attempt(false, null, ex.getMessage());
        }
    }

    private static String gotifyEndpoint(String baseUrl, String appToken) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/message?token=" + URLEncoder.encode(appToken, StandardCharsets.UTF_8);
    }

    /** Gotify's push shape: {@code title}/{@code message}/{@code priority},
     *  plus the {@code client::display} extra so a message body containing
     *  markdown (bold, code, links) actually renders as such in the Gotify
     *  apps instead of showing the raw asterisks/backticks. */
    private static Map<String, Object> gotifyPayload(String eventType, String target, Map<String, Object> data) {
        StringBuilder message = new StringBuilder();
        message.append("**").append(target).append("**");
        if (data != null && !data.isEmpty()) {
            data.forEach((k, v) -> message.append("\n- ").append(k).append(": `").append(v).append('`'));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "islandr — " + friendlyEventLabel(eventType));
        payload.put("message", message.toString());
        payload.put("priority", gotifyPriority(eventType));
        payload.put("extras", Map.of("client::display", Map.of("contentType", "text/markdown")));
        return payload;
    }

    /** 1 = low, 5 = normal, 10 = urgent (Gotify's own scale) — failures and
     *  a peer going dark get bumped above the routine "something changed"
     *  events, so they can be set to break through Do-Not-Disturb in the
     *  Gotify client if the admin configures it that way. */
    private static int gotifyPriority(String eventType) {
        if (WebhookEventType.ACME_CERT_RENEWAL_FAILED.equals(eventType)
                || WebhookEventType.PEER_DISCONNECTED.equals(eventType)) {
            return 8;
        }
        return 5;
    }

    private static String friendlyEventLabel(String eventType) {
        return switch (eventType) {
            case WebhookEventType.PEER_CONNECTED -> "Peer connected";
            case WebhookEventType.PEER_DISCONNECTED -> "Peer disconnected";
            case WebhookEventType.PEER_ENABLED -> "Peer enabled";
            case WebhookEventType.PEER_DISABLED -> "Peer disabled";
            case WebhookEventType.ACL_GRANT_CREATED -> "ACL grant created";
            case WebhookEventType.ACL_GRANT_REVOKED -> "ACL grant revoked";
            case WebhookEventType.DISCOVERY_SCAN_COMPLETED -> "Device discovery scan completed";
            case WebhookEventType.ACME_CERT_RENEWED -> "Certificate renewed";
            case WebhookEventType.ACME_CERT_RENEWAL_FAILED -> "Certificate renewal failed";
            case WebhookEventType.TEST -> "Test notification";
            default -> eventType;
        };
    }

    private static Map<String, Object> genericEnvelope(String eventType, String actor, String target, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", eventType);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("actor", actor);
        envelope.put("target", target);
        envelope.put("data", data == null ? Map.of() : data);
        return envelope;
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
