package de.chriscohnen.islandr.apikey;

import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.auth.SessionFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Resolves an {@code Authorization: Bearer <key>} header on every request
 * and, if it validates, populates the same {@link AuthContext} shape
 * {@link SessionFilter} uses for session-cookie auth — so {@code Auth.require}/
 * {@code Auth.requireAdmin} work identically regardless of which credential
 * actually authenticated the caller (issue #15, ADR-0026).
 *
 * <p>Runs alongside {@link SessionFilter}, not instead of it: a request
 * carrying a valid Bearer token authenticates via this filter, a request
 * carrying a valid session cookie authenticates via that one. In practice a
 * caller presents exactly one of the two (a script has a token, a browser
 * has a cookie), so there's no real collision to resolve between them.
 */
@Provider
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Inject ApiKeyService apiKeys;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String header = ctx.getHeaderString("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) return;
        String raw = header.substring(BEARER_PREFIX.length()).trim();
        if (raw.isBlank()) return;

        ApiKey key = apiKeys.authenticate(raw);
        if (key == null) return; // invalid/revoked — leave unauthenticated, not an error

        // API keys are full-admin-equivalent in v1 (ADR-0026, R-184) — no
        // per-key scoping yet. userId stays null, same shape as the local
        // ENV-bootstrap admin; "apikey" as the provider value distinguishes
        // it from every session-based path without needing a new field.
        ctx.setProperty(SessionFilter.CTX_AUTH,
                new AuthContext("apikey:" + key.label, null, "apikey", true));
    }
}
