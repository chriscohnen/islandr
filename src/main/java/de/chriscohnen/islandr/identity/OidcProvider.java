package de.chriscohnen.islandr.identity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per OIDC provider (key = {@code microsoft} | {@code google}).
 * Both rows are seeded disabled by V5. tenant_id is only meaningful for
 * Microsoft (single-tenant). allowed_domains is a CSV — only emails in
 * one of these domains may auto-provision a user record on first login.
 *
 * <p>Stored secrets ({@link #clientSecret}) are intentionally not encrypted
 * at-rest: the host root account already has access to the SQLite file, so
 * an encryption key that lives on the same host adds ceremony without raising
 * the trust boundary. See CLAUDE.md / PRD §Identity.
 */
@Entity
@Table(name = "oidc_providers")
public class OidcProvider extends PanacheEntityBase {

    public static final String MICROSOFT = "microsoft";
    public static final String GOOGLE = "google";

    @Id
    @Column(name = "provider_key", nullable = false, length = 32)
    public String providerKey;

    @Column(name = "enabled", nullable = false)
    public boolean enabled;

    @Column(name = "client_id", length = 255)
    public String clientId;

    @Column(name = "client_secret", length = 512)
    public String clientSecret;

    /** Required for Microsoft, ignored for Google. */
    @Column(name = "tenant_id", length = 255)
    public String tenantId;

    /** CSV of email domains permitted to auto-provision. Empty means "no one auto-provisions". */
    @Column(name = "allowed_domains", columnDefinition = "TEXT")
    public String allowedDomains;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    public String updatedBy;

    public boolean isMicrosoft() {
        return MICROSOFT.equals(providerKey);
    }
}
