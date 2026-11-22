package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.webhook.WebhookDispatcher;
import de.chriscohnen.islandr.webhook.WebhookEventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook events for user lifecycle changes (webhook-user-events), so another
 * system can provision the same people. One place builds the payload — id,
 * e-mail, name, role ids, enabled flag, source — and never carries a password
 * hash or an avatar. Callers pass the {@code source} ("local", "oidc",
 * "import", "api") and, for an update, the names of the fields that changed.
 */
@ApplicationScoped
public class UserEvents {

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_OIDC = "oidc";
    public static final String SOURCE_IMPORT = "import";
    public static final String SOURCE_API = "api";

    @Inject WebhookDispatcher webhooks;
    @Inject EntityManager em;
    @Inject TransactionSynchronizationRegistry txRegistry;

    public void created(User u, String actor, String source) {
        publish(WebhookEventType.USER_CREATED, u, actor, source, null);
    }

    public void updated(User u, String actor, String source, List<String> changedFields) {
        if (changedFields == null || changedFields.isEmpty()) return;
        publish(WebhookEventType.USER_UPDATED, u, actor, source, changedFields);
    }

    public void enabledChanged(User u, String actor, String source) {
        publish(u.enabled ? WebhookEventType.USER_ENABLED : WebhookEventType.USER_DISABLED,
                u, actor, source, null);
    }

    /** Call before the row is deleted — the role links go with it. */
    public void deleted(User u, String actor, String source) {
        publish(WebhookEventType.USER_DELETED, u, actor, source, null);
    }

    private void publish(String type, User u, String actor, String source, List<String> changed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.id);
        data.put("email", u.email);
        data.put("name", u.name);
        data.put("roleIds", roleIds(u.id));
        data.put("enabled", u.enabled);
        data.put("source", source);
        if (changed != null) data.put("changedFields", changed);
        String target = "User:" + u.id;
        // Only after the change is committed: the delivery thread records its outcome on the
        // webhooks table at once, and doing that while this request's transaction still holds
        // its locks makes SQLite's shared cache answer one of them with SQLITE_LOCKED. It also
        // means a rolled-back change sends nothing.
        if (txRegistry.getTransactionStatus() == Status.STATUS_ACTIVE) {
            txRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() {}
                @Override public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) webhooks.publish(type, actor, target, data);
                }
            });
        } else {
            webhooks.publish(type, actor, target, data);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> roleIds(String userId) {
        return (List<String>) em.createNativeQuery(
                        "SELECT role_id FROM user_roles WHERE user_id = ?1 ORDER BY role_id")
                .setParameter(1, userId).getResultList();
    }
}
