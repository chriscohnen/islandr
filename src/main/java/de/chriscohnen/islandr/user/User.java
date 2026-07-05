package de.chriscohnen.islandr.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "nickname")
    public String nickname;

    @NotBlank
    @Email
    @Column(name = "email", nullable = false, unique = true)
    public String email;

    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    @Column(name = "is_admin", nullable = false)
    public boolean isAdmin = false;

    // PBKDF2 PHC string for local password login (F-01a). Null = no local password
    // (OIDC-only, or the ENV-admin path). Never serialised to any DTO.
    @Column(name = "password_hash")
    public String passwordHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    // --- Identity linkage (V5) -----------------------------------------------
    // Null for local-only users. (provider, subject) is the stable identity from
    // the IdP; email may change in the IdP and we still find the right row.
    @Column(name = "oidc_provider", length = 32)
    public String oidcProvider;

    @Column(name = "oidc_subject", length = 255)
    public String oidcSubject;

    // --- Avatar cache (V5) ---------------------------------------------------
    // Bytes from MS Graph / Google picture / Gravatar. Null = no avatar known.
    @Column(name = "avatar_bytes")
    public byte[] avatarBytes;

    @Column(name = "avatar_content_type", length = 64)
    public String avatarContentType;

    @Column(name = "avatar_etag", length = 64)
    public String avatarEtag;

    @Column(name = "avatar_fetched_at")
    public Instant avatarFetchedAt;

    /** "gravatar" | "oidc" | null — see V7 migration. */
    @Column(name = "avatar_source", length = 16)
    public String avatarSource;

    /** "de" | "en" | null. Set from OIDC locale claim on first login; updated by user preference. */
    @Column(name = "preferred_locale", length = 8)
    public String preferredLocale;

    public static User createNew(String name, String email) {
        User u = new User();
        u.id = UUID.randomUUID().toString();
        u.name = name;
        u.email = email;
        u.enabled = true;
        u.createdAt = Instant.now();
        return u;
    }

    public static User findByOidc(String provider, String subject) {
        return find("oidcProvider = ?1 and oidcSubject = ?2", provider, subject).firstResult();
    }
}

