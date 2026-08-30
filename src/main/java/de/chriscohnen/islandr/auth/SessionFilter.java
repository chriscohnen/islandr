package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.user.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.ext.Provider;

/**
 * Resolves the session cookie on every request and stashes the active session
 * plus a derived {@link AuthContext} on the JAX-RS context, so downstream
 * resources can read it without re-querying. Endpoint-level enforcement lives
 * in {@link Auth#require}/{@link Auth#requireAdmin} — those throw 401/403 when
 * the property is missing or insufficient.
 */
@Provider
public class SessionFilter implements ContainerRequestFilter {

    public static final String COOKIE_NAME = "islandr_session";
    public static final String CTX_SESSION = "islandr.session";
    public static final String CTX_AUTH = "islandr.auth";

    @Inject SessionService sessions;

    @Override
    public void filter(ContainerRequestContext ctx) {
        Cookie c = ctx.getCookies().get(COOKIE_NAME);
        if (c == null) return;
        Session s = sessions.findActive(c.getValue());
        if (s == null) return;
        AuthContext auth = resolveAuth(s);
        // A session outliving the access it was issued for is no session at
        // all (issue #53). Without this, disabling a user — or their access
        // window closing — only took effect at their next login, so anyone
        // already signed in kept full access for the rest of the session's
        // lifetime, self-service peer creation included.
        if (auth == null) return;
        ctx.setProperty(CTX_SESSION, s);
        ctx.setProperty(CTX_AUTH, auth);
    }

    private AuthContext resolveAuth(Session s) {
        // Local ENV-bootstrap admin: no users row, always admin.
        if (s.isLocalAdmin()) {
            return new AuthContext(s.principal, null, s.provider, true);
        }
        // Org user: re-read is_admin from the row each request, so promotion
        // takes effect immediately without forcing a re-login.
        boolean admin = false;
        if (s.userId != null) {
            User u = User.findById(s.userId);
            // A deleted user's session stops resolving, same as one whose
            // access has been withdrawn.
            if (u == null || !u.accessAllowedAt(java.time.Instant.now())) return null;
            admin = u.isAdmin;
        }
        return new AuthContext(s.principal, s.userId, s.provider, admin);
    }
}
