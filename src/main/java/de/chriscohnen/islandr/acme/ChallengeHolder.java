package de.chriscohnen.islandr.acme;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The single in-flight HTTP-01 challenge, if any (ADR-0019). One instance
 * (islandr manages exactly one ACME-issued certificate/domain in v1), so a
 * plain pair of volatile fields is enough — no need for a token→value map.
 *
 * <p>This is the entirety of {@link AcmeChallengeResource}'s trust boundary
 * (risk R-164): the endpoint only ever answers with whatever is set here, and
 * only when the requested token matches exactly.
 */
@ApplicationScoped
public class ChallengeHolder {

    private volatile String token;
    private volatile String keyAuthorization;

    void set(String token, String keyAuthorization) {
        this.token = token;
        this.keyAuthorization = keyAuthorization;
    }

    void clear() {
        this.token = null;
        this.keyAuthorization = null;
    }

    /** Null unless {@code requestedToken} is exactly the current in-flight token. */
    String keyAuthorizationFor(String requestedToken) {
        String t = this.token;
        return (t != null && t.equals(requestedToken)) ? this.keyAuthorization : null;
    }
}
