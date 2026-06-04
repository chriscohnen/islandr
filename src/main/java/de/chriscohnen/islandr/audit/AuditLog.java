package de.chriscohnen.islandr.audit;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per mutating action (PRD F-10). Append-only — no UPDATE, no DELETE.
 * The {@code metaJson} blob carries a redacted before/after diff serialised
 * by {@link AuditService}; never write to it directly.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    /**
     * Display name of the principal that caused the action: usually an email,
     * 'admin' for the local ENV bootstrap, or 'system:<reason>' for startup
     * tasks. Free-form on purpose — actor is a frozen string, not a FK.
     */
    @Column(name = "actor", nullable = false, length = 255)
    public String actor;

    /**
     * Structured action name in {@code domain.verb} form, e.g. {@code peer.create},
     * {@code user.admin_grant}, {@code settings.update}, {@code oidc_provider.enable}.
     * Kept short so the UI can group/filter without an extra lookup table.
     */
    @Column(name = "action", nullable = false, length = 64)
    public String action;

    /**
     * Free-form pointer to the affected entity. Convention: {@code TypeName:id}
     * (e.g. {@code Peer:abc-…}, {@code User:def-…}, {@code Settings:singleton},
     * {@code OidcProvider:google}). Null when the action has no scoped target.
     */
    @Column(name = "target", length = 255)
    public String target;

    /**
     * JSON blob with the redacted before/after diff. Schema is whatever the
     * caller of {@link AuditService#log} passes — typically
     * {@code { "before": {...}, "after": {...} }} with only the changed keys.
     * Never store {@code client_secret}, {@code private_key_pem} etc. here;
     * the diff helper redacts them before they reach this column.
     */
    @Column(name = "meta_json", columnDefinition = "TEXT")
    public String metaJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static AuditLog of(String actor, String action, String target, String metaJson) {
        AuditLog a = new AuditLog();
        a.id = UUID.randomUUID().toString();
        a.actor = actor;
        a.action = action;
        a.target = target;
        a.metaJson = metaJson;
        a.createdAt = Instant.now();
        return a;
    }
}
