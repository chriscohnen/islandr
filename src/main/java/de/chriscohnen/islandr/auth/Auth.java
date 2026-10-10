package de.chriscohnen.islandr.auth;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Single entry point for resource methods to assert what authorisation they need.
 * Endpoints that need an authenticated user call {@link #require}; admin-only
 * endpoints call {@link #requireAdmin}. The {@link AuthContext} is populated by
 * {@link SessionFilter} on every request that carries a valid session cookie.
 */
public final class Auth {

    private Auth() {}

    public static AuthContext require(ContainerRequestContext ctx) {
        AuthContext a = (AuthContext) ctx.getProperty(SessionFilter.CTX_AUTH);
        if (a == null) throw new NotAuthorizedException("authentication required");
        return a;
    }

    public static AuthContext requireAdmin(ContainerRequestContext ctx) {
        AuthContext a = require(ctx);
        if (!a.isAdmin()) throw new ForbiddenException("admin role required");
        return a;
    }

    /** Admin-level auth, additionally gated by an API-key scope
     *  (apikey-scopes) when the caller is an API key — a no-op beyond
     *  {@link #requireAdmin} for a session, since scopes don't apply to a
     *  human. Facade endpoints (the external API, {@code ConfigResource}'s
     *  export/import) use this instead of {@link #requireAdmin} so a
     *  read-scoped key gets 403 on a write, and vice versa. */
    public static AuthContext requireScope(ContainerRequestContext ctx, String scope) {
        AuthContext a = requireAdmin(ctx);
        if (!a.hasScope(scope)) {
            throw new ForbiddenException("API key lacks required scope: " + scope);
        }
        return a;
    }

    /** Returns the active {@link AuthContext} or {@code null} if no session. */
    public static AuthContext current(ContainerRequestContext ctx) {
        return (AuthContext) ctx.getProperty(SessionFilter.CTX_AUTH);
    }
}
