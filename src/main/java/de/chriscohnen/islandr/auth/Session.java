package de.chriscohnen.islandr.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Server-side session record. The id is a random 32-byte base64url string
 * (sent as an HttpOnly cookie). Local-admin sessions store {@link #userId} = null
 * — the ENV-bootstrap admin has no row in {@code users}. The {@link #principal}
 * column carries the display name for both cases.
 */
@Entity
@Table(name = "sessions")
public class Session extends PanacheEntityBase {

    public static final String LOCAL = "local";
    public static final String MICROSOFT = "microsoft";
    public static final String GOOGLE = "google";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    public String id;

    /** null for the ENV-bootstrap admin (no users row). */
    @Column(name = "user_id", length = 36)
    public String userId;

    @Column(name = "principal", nullable = false, length = 255)
    public String principal;

    @Column(name = "provider", nullable = false, length = 32)
    public String provider;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean isLocalAdmin() {
        return LOCAL.equals(provider);
    }
}
