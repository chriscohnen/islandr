package de.chriscohnen.islandr.apikey;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An admin-issued key for the external automation API (issue #15,
 * ADR-0026). Acts with full admin privileges — no per-key scoping in v1
 * (ADR-0026, R-184). Only {@link #keyHash} is stored; the raw key is shown
 * to the admin exactly once, at creation.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "label", nullable = false)
    public String label;

    /** SHA-256 hex digest of the raw key — never the raw value itself. */
    @Column(name = "key_hash", nullable = false, length = 128)
    public String keyHash;

    /** Non-secret leading slice of the raw key, kept in plaintext so the
     *  admin can identify a key in the list view without re-exposing it. */
    @Column(name = "key_prefix", nullable = false, length = 24)
    public String keyPrefix;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 255)
    public String createdBy;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null;
    }
}
