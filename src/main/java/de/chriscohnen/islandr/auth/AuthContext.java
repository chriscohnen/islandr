package de.chriscohnen.islandr.auth;

/**
 * Flattened auth view derived from the {@link Session} for one request.
 *
 * The local ENV-bootstrap admin has {@code userId == null} and is always admin.
 * Org users carry their {@code users.is_admin} flag, resolved at filter time
 * (one extra row lookup per request — cheap, and avoids stale per-session state
 * when an admin promotes/demotes a user mid-session).
 */
public record AuthContext(String principal, String userId, String provider, boolean isAdmin) {

    public boolean isLocalAdmin() {
        return Session.LOCAL.equals(provider) && userId == null;
    }
}
