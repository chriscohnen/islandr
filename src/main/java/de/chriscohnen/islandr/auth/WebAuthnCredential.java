package de.chriscohnen.islandr.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A registered authenticator (ADR-0028, issue #67).
 *
 * <p>Scoped to {@link #LOCAL_ADMIN} rather than to the configured admin
 * username: the ENV bootstrap admin has no {@code users} row — its identity is
 * the principal string — so keying on that name would mean renaming
 * {@code ISLANDR_ADMIN_USER} silently orphans every registered key. A rename is
 * a configuration change and must not become an authentication event.
 *
 * <p>Several rows per subject are expected. One credential makes handover
 * impossible without a gap, and a lost key with no second one registered is a
 * lockout of exactly the account that exists to recover from lockouts.
 */
@Entity
@Table(name = "webauthn_credentials")
public class WebAuthnCredential extends PanacheEntityBase {

    /** The local recovery admin, as a fixed marker rather than a username. */
    public static final String LOCAL_ADMIN = "local-admin";

    @Id @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "subject", nullable = false, length = 64)
    public String subject;

    /** Base64url, as the browser reports it. */
    @Column(name = "credential_id", nullable = false, length = 512)
    public String credentialId;

    /** The host the browser saw when this credential was registered. WebAuthn
     *  binds the credential to it, so a key enrolled under one name is silently
     *  not offered under another. */
    @Column(name = "rp_id", nullable = false, length = 253)
    public String rpId;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    public String publicKey;

    /** Monotonic per credential. An assertion whose counter has not advanced is
     *  what a cloned authenticator produces, so it is refused. Authenticators
     *  that never count report 0 forever, which is legal and handled by the
     *  verifier rather than here. */
    @Column(name = "sign_count", nullable = false)
    public long signCount;

    @Column(name = "label", length = 100)
    public String label;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    public static WebAuthnCredential createNew(String subject, String rpId, String credentialId,
                                               String publicKey, long signCount, String label) {
        WebAuthnCredential c = new WebAuthnCredential();
        c.id = UUID.randomUUID().toString();
        c.subject = subject;
        c.rpId = rpId;
        c.credentialId = credentialId;
        c.publicKey = publicKey;
        c.signCount = signCount;
        c.label = (label == null || label.isBlank()) ? null : label.strip();
        c.createdAt = Instant.now();
        return c;
    }

    public static List<WebAuthnCredential> forSubject(String subject) {
        return list("subject", subject);
    }

    /** Only the credentials this host can actually offer. Listing the others
     *  would mean showing a key the browser will not present. */
    public static List<WebAuthnCredential> forSubjectAndRp(String subject, String rpId) {
        return list("subject = ?1 and rpId = ?2", subject, rpId);
    }

    public static WebAuthnCredential byCredentialId(String credentialId) {
        return find("credentialId", credentialId).firstResult();
    }
}
