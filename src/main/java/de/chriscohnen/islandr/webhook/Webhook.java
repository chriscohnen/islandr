package de.chriscohnen.islandr.webhook;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An admin-configured outgoing webhook (issue #68) — a URL, an HMAC secret,
 * and a filter of which {@link WebhookEventType} keys it wants delivered.
 */
@Entity
@Table(name = "webhooks")
public class Webhook extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "url", nullable = false, length = 1024)
    public String url;

    @Column(name = "description", length = 255)
    public String description;

    @Column(name = "secret", nullable = false, length = 128)
    public String secret;

    @Column(name = "event_types", nullable = false, columnDefinition = "TEXT")
    public String eventTypes;

    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    @Column(name = "last_delivery_at")
    public Instant lastDeliveryAt;

    /** "ok" | "failed" | null (never delivered yet). */
    @Column(name = "last_delivery_status", length = 16)
    public String lastDeliveryStatus;

    @Column(name = "last_delivery_error", columnDefinition = "TEXT")
    public String lastDeliveryError;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    public String updatedBy;

    public Set<String> eventTypeSet() {
        if (eventTypes == null || eventTypes.isBlank()) return Set.of();
        Set<String> set = new LinkedHashSet<>();
        for (String s : eventTypes.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    public boolean isSubscribed(String eventType) {
        return eventTypeSet().contains(eventType);
    }

    public static String toCsv(java.util.Collection<String> types) {
        if (types == null) return "";
        return String.join(",", new LinkedHashSet<>(types));
    }
}
