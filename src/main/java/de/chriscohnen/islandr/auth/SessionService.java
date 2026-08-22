package de.chriscohnen.islandr.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@ApplicationScoped
public class SessionService {

    public static final Duration TTL = Duration.ofHours(12);

    // Lazily initialised — Quarkus native-image refuses to seed a SecureRandom
    // during build-time bean instantiation (the seed source is a native handle).
    // Volatile + double-checked is fine: newId() is not on a hot path.
    private volatile SecureRandom rng;

    @Transactional
    public Session create(String provider, String principal, String userId) {
        return create(provider, principal, userId, null);
    }

    /** @param customProviderId only meaningful when {@code provider} is
     *         {@link Session#CUSTOM} — which of the admin-configured generic
     *         OIDC providers (issue #69) this session came from. */
    @Transactional
    public Session create(String provider, String principal, String userId, String customProviderId) {
        Session s = new Session();
        s.id = newId();
        s.provider = provider;
        s.oidcCustomProviderId = customProviderId;
        s.principal = principal;
        s.userId = userId;
        s.createdAt = Instant.now();
        s.expiresAt = s.createdAt.plus(TTL);
        s.persist();
        return s;
    }

    /** Returns the active session for the given id, or null. */
    public Session findActive(String id) {
        if (id == null || id.isBlank()) return null;
        Session s = Session.findById(id);
        if (s == null) return null;
        return s.isActive(Instant.now()) ? s : null;
    }

    @Transactional
    public void revoke(String id) {
        if (id == null || id.isBlank()) return;
        Session s = Session.findById(id);
        if (s != null && s.revokedAt == null) {
            s.revokedAt = Instant.now();
        }
    }

    /** 32 random bytes → 43-char URL-safe base64 (no padding). */
    private String newId() {
        byte[] buf = new byte[32];
        rng().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private SecureRandom rng() {
        SecureRandom r = rng;
        if (r == null) {
            synchronized (this) {
                r = rng;
                if (r == null) {
                    r = new SecureRandom();
                    rng = r;
                }
            }
        }
        return r;
    }
}
