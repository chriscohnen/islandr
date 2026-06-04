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

    /** Returns the active {@link AuthContext} or {@code null} if no session. */
    public static AuthContext current(ContainerRequestContext ctx) {
        return (AuthContext) ctx.getProperty(SessionFilter.CTX_AUTH);
    }
}
