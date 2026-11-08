package de.chriscohnen.islandr.webhook;

import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * Deletes every webhook between tests. A delivery thread from the previous
 * test may still be recording a status on one of them, and the shared-cache
 * test database answers that overlap with SQLITE_LOCKED at once rather than
 * waiting — the same contention {@code WebhookDispatcher#record} retries on in
 * production code. A plain {@code @Transactional deleteAll()} failed the full
 * suite this way, in more than one test class.
 */
public final class WebhookTestCleanup {

    private WebhookTestCleanup() {}

    public static void deleteAllWebhooks() {
        for (int i = 0; ; i++) {
            try {
                QuarkusTransaction.requiringNew().run(() -> Webhook.deleteAll());
                return;
            } catch (RuntimeException e) {
                if (i >= 20) throw e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
