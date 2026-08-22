package de.chriscohnen.islandr.webhook;

import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Delivery, HMAC signing, event-type filtering, and retry behavior of
 *  {@link WebhookDispatcher} (issue #68) — HTTP layer replaced by
 *  {@link FakeHttpFetcher}, no real network involved. */
@QuarkusTest
class WebhookDispatcherTest {

    @Inject WebhookDispatcher dispatcher;
    @Inject WebhookService svc;
    @Inject FakeHttpFetcher http;

    @BeforeEach
    @Transactional
    void reset() {
        Webhook.deleteAll();
        http.reset();
    }

    @Test
    void publish_deliversToSubscribedWebhookWithValidSignature() throws Exception {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://hook.example.com/a", null,
                List.of(WebhookEventType.PEER_CONNECTED), null, null), "admin").webhook();
        http.postBodyStub(w.url, 200, "ok", null);

        dispatcher.publish(WebhookEventType.PEER_CONNECTED, "admin", "Peer:p1", Map.of("peerId", "p1"));

        waitUntil(() -> http.calls.stream().anyMatch(c -> c.url().equals(w.url)), 2000);
        assertThat(http.calls).anyMatch(c -> c.url().equals(w.url));

        var call = http.calls.stream().filter(c -> c.url().equals(w.url)).findFirst().orElseThrow();
        String signatureHeader = call.headers().get("X-Islandr-Signature");
        assertThat(signatureHeader).startsWith("sha256=");
        assertThat(signatureHeader.substring("sha256=".length()))
                .isEqualTo(hmacHex(w.secret, call.rawBody()));
        assertThat(call.rawBodyText()).contains("\"event\":\"peer.connected\"");

        waitUntil(() -> "ok".equals(readWebhook(w.id).lastDeliveryStatus), 2000);
        assertThat(readWebhook(w.id).lastDeliveryStatus).isEqualTo("ok");
    }

    @Test
    void publish_skipsWebhookNotSubscribedToThisEventType() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://hook.example.com/b", null,
                List.of(WebhookEventType.ACL_GRANT_CREATED), null, null), "admin").webhook();
        http.postBodyStub(w.url, 200, "ok", null);

        dispatcher.publish(WebhookEventType.PEER_CONNECTED, "admin", "Peer:p1", Map.of());

        // Give any (wrongly fired) async delivery a moment, then assert it never happened.
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        assertThat(http.calls).noneMatch(c -> c.url().equals(w.url));
    }

    @Test
    void publish_skipsDisabledWebhook() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://hook.example.com/c", null,
                List.of(WebhookEventType.PEER_CONNECTED), null, null), "admin").webhook();
        svc.update(w.id, new WebhookDto.UpdateRequest(null, null, null, false, null, null), "admin");
        http.postBodyStub(w.url, 200, "ok", null);

        dispatcher.publish(WebhookEventType.PEER_CONNECTED, "admin", "Peer:p1", Map.of());

        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        assertThat(http.calls).noneMatch(c -> c.url().equals(w.url));
    }

    @Test
    void publish_recordsFailureWhenReceiverReturnsError() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://hook.example.com/d", null,
                List.of(WebhookEventType.PEER_CONNECTED), null, null), "admin").webhook();
        http.postBodyStub(w.url, 500, "boom", null);

        dispatcher.publish(WebhookEventType.PEER_CONNECTED, "admin", "Peer:p1", Map.of());

        // First attempt happens immediately, before any backoff sleep.
        waitUntil(() -> http.calls.stream().anyMatch(c -> c.url().equals(w.url)), 2000);
        assertThat(http.calls).anyMatch(c -> c.url().equals(w.url));
    }

    @Test
    void testFire_sendsRegardlessOfEventTypeFilter_andReturnsResultSynchronously() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://hook.example.com/e", null,
                List.of(WebhookEventType.ACL_GRANT_CREATED), null, null), "admin").webhook(); // NOT subscribed to "webhook.test"
        http.postBodyStub(w.url, 200, "ok", null);

        WebhookDto.TestFireResponse result = dispatcher.testFire(w.id);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(http.calls).anyMatch(c -> c.url().equals(w.url));
    }

    @Test
    void publish_gotifyFormat_postsToMessageEndpointWithNativeShapeAndNoSignature() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://gotify.example.com", null,
                List.of(WebhookEventType.PEER_DISCONNECTED), WebhookFormat.GOTIFY, "my-token"), "admin").webhook();
        String expectedEndpoint = "https://gotify.example.com/message?token=my-token";
        http.postBodyStub(expectedEndpoint, 200, "ok", null);

        dispatcher.publish(WebhookEventType.PEER_DISCONNECTED, "system", "Peer:p1", Map.of("name", "office-gw"));

        waitUntil(() -> http.calls.stream().anyMatch(c -> c.url().equals(expectedEndpoint)), 2000);
        var call = http.calls.stream().filter(c -> c.url().equals(expectedEndpoint)).findFirst().orElseThrow();

        assertThat(call.headers()).doesNotContainKey("X-Islandr-Signature");
        assertThat(call.rawBodyText()).contains("\"title\"").contains("\"message\"").contains("\"priority\":8");
        assertThat(call.rawBodyText()).doesNotContain("\"event\""); // not the generic envelope shape
    }

    @Test
    void testFire_unknownWebhook_returnsFailureNotException() {
        WebhookDto.TestFireResponse result = dispatcher.testFire("does-not-exist");
        assertThat(result.success()).isFalse();
    }

    private static String hmacHex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(body);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Transactional
    Webhook readWebhook(String id) {
        return Webhook.findById(id);
    }

    /** No awaitility dependency in this project — a small manual poll loop
     *  is enough for these bounded async-delivery assertions. */
    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
    }
}
