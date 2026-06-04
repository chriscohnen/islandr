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
