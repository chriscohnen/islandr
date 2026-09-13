package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.identity.AvatarFetcher;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;

/**
 * Pulled out of {@code UserAvatarResource} so the Gravatar-fetch + cache step
 * runs under a real CDI-managed {@code @Transactional} boundary. Direct
 * self-invocation inside a JAX-RS resource bean does not trigger interceptors,
 * which used to leave the avatar bytes unpersisted.
 */
@ApplicationScoped
public class AvatarService {

    @Inject AvatarFetcher avatars;
    @Inject SettingsService settings;

    public record Result(byte[] bytes, String contentType, String etag) {}

    /** Returns the avatar to serve, or null for 404. */
    public Result lookup(String userId) {
        User u = User.findById(userId);
        if (u == null) throw new NotFoundException("user not found: " + userId);

        if (u.avatarBytes == null && u.oidcProvider == null && settings.get().gravatarEnabled) {
            u = fetchAndCacheGravatar(userId);
        }
        if (u.avatarBytes == null) return null;
        return new Result(u.avatarBytes, u.avatarContentType, u.avatarEtag);
    }

    /**
     * Stores an uploaded avatar (issue #85). Until this existed, a face could
     * only ever arrive through an OIDC login's profile photo or through
     * Gravatar — which means the browser fetching from gravatar.com, the one
     * outbound call the product is otherwise built not to make. A hub running
     * purely on local accounts had the choice between initials for everyone
     * and switching on the thing its operator came here to avoid.
     *
     * <p>Initials stay the fallback; they are a designed part of the system,
     * not a placeholder to eliminate.
     *
     * @return the etag of the stored image, for the caller's cache headers
     */
    @Transactional
    public String store(String userId, byte[] bytes, String declaredContentType) {
        User u = User.findById(userId);
        if (u == null) throw new NotFoundException("user not found: " + userId);
        AvatarImage.Meta meta = AvatarImage.validate(bytes, declaredContentType);

        u.avatarBytes = bytes;
        u.avatarContentType = meta.contentType();
        // Content-addressed, so a re-upload of the same image keeps the same
        // etag and browsers do not refetch it.
        u.avatarEtag = etagOf(bytes);
        u.avatarFetchedAt = Instant.now();
        return u.avatarEtag;
    }

    /**
     * Drops the stored bytes. The user falls back to whatever the chain would
     * have produced anyway — Gravatar if it is enabled and the account is
     * local, initials otherwise.
     */
    @Transactional
    public void clear(String userId) {
        User u = User.findById(userId);
        if (u == null) throw new NotFoundException("user not found: " + userId);
        u.avatarBytes = null;
        u.avatarContentType = null;
        u.avatarEtag = null;
        u.avatarFetchedAt = null;
    }

    private static String etagOf(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    @Transactional
    User fetchAndCacheGravatar(String userId) {
        // Re-load inside the TX so changes are tracked by the open persistence context.
        User u = User.findById(userId);
        if (u == null) return null;
        AvatarFetcher.Avatar a = avatars.fetchGravatar(u.email);
        if (a == null) return u;
        u.avatarBytes = a.bytes();
        u.avatarContentType = a.contentType();
        u.avatarEtag = a.etag();
        u.avatarFetchedAt = Instant.now();
        return u;
    }
}
