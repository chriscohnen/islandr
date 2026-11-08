package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import de.chriscohnen.islandr.webhook.Webhook;
import de.chriscohnen.islandr.webhook.WebhookDto;
import de.chriscohnen.islandr.webhook.WebhookEventType;
import de.chriscohnen.islandr.webhook.WebhookService;
import de.chriscohnen.islandr.webhook.WebhookTestCleanup;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** monitoring-status-endpoint (#71): one webhook per failure streak, not one per retry. */
@QuarkusTest
class FirewallApplyFailedWebhookTest {

    @Inject RulesetService rulesets;
    @Inject NftablesAdapter nftAdapter;
    @Inject WebhookService webhooks;
    @Inject FakeHttpFetcher http;

    private String url;

    private MockNftablesAdapter nft() {
        return (MockNftablesAdapter) ClientProxy.unwrap(nftAdapter);
    }

    @BeforeEach
    void setUp() {
        WebhookTestCleanup.deleteAllWebhooks();
        QuarkusTransaction.requiringNew().run(this::seed);
    }

    private void seed() {
        http.reset();
        nft().resetForTests();
        FirewallState s = FirewallState.get();
        s.lastStatus = FirewallState.OK;
        url = "https://hook.example.com/fw-" + System.nanoTime();
        webhooks.create(new WebhookDto.CreateRequest(url, null,
                List.of(WebhookEventType.FIREWALL_APPLY_FAILED), null, null, null, null), "admin");
        http.postBodyStub(url, 200, "ok", null);
    }

    @AfterEach
    void tearDown() {
        nft().resetForTests();
        WebhookTestCleanup.deleteAllWebhooks();
    }

    private long deliveries() {
        return http.calls.stream().filter(c -> c.url().equals(url)).count();
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    @Test
    void failingApply_firesOnce_perStreak_andAgainAfterARecovery() throws Exception {
        nft().forceFailure = "syntax error";
        rulesets.recomputeAndApply("test");
        waitUntil(() -> deliveries() >= 1, 2000);
        rulesets.recomputeAndApply("test");   // still failing: same streak
        Thread.sleep(300);
        assertThat(deliveries()).isEqualTo(1);

        nft().forceFailure = null;
        rulesets.recomputeAndApply("test");   // recovered
        nft().forceFailure = "syntax error again";
        rulesets.recomputeAndApply("test");   // a new streak
        waitUntil(() -> deliveries() >= 2, 2000);
        assertThat(deliveries()).isEqualTo(2);
    }
}
