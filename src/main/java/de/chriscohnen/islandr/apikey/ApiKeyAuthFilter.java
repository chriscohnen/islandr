package de.chriscohnen.islandr.apikey;

import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.auth.SessionFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Set;

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
 *
 * <p><b>Facade boundary (apikey-scopes):</b> unless a key holds {@code full},
 * it is confined to {@code /api/external/v1/} plus the one or two console
 * endpoints a {@code config:*} scope specifically names — every other
 * console path is rejected right here, before the endpoint's own
 * {@code Auth.requireScope} ever runs. This is the fence; per-operation
 * read/write scopes inside the facade are the second layer, enforced by
 * each endpoint individually.
 */
@Provider
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String EXTERNAL_FACADE_PREFIX = "api/external/v1/";

    @Inject ApiKeyService apiKeys;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String header = ctx.getHeaderString("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) return;
        String raw = header.substring(BEARER_PREFIX.length()).trim();
        if (raw.isBlank()) return;

        ApiKey key = apiKeys.authenticate(raw);
        if (key == null) return; // invalid/revoked — leave unauthenticated, not an error

        List<String> scopeList = apiKeys.scopesOf(key.id);
        Set<String> scopes = Set.copyOf(scopeList);
        boolean full = scopes.contains(ApiKeyScope.FULL);

        if (!full && !reachesConsoleUnderItsOwnScope(ctx, scopes) && !isWithinFacade(ctx)) {
            throw new ForbiddenException(
                    "this API key is confined to /api/external/v1/ and the config scope(s) it holds");
        }

        // userId stays null, same shape as the local ENV-bootstrap admin;
        // "apikey" as the provider value distinguishes it from every
        // session-based path without needing a new field. isAdmin stays
        // true even for a narrowly-scoped key — requireAdmin only
        // establishes "this is a privileged caller", same as it always has;
        // requireScope is what actually narrows what it may do.
        ctx.setProperty(SessionFilter.CTX_AUTH,
                new AuthContext("apikey:" + key.label, null, "apikey", true, scopes));
    }

    // Same leading-slash defense ExternalApiToggleFilter already applies to
    // the same kind of path comparison — getUriInfo().getPath() is not
    // guaranteed slash-free across every request path.
    private static String normalizedPath(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static boolean isWithinFacade(ContainerRequestContext ctx) {
        return normalizedPath(ctx).startsWith(EXTERNAL_FACADE_PREFIX);
    }

    /** The two console endpoints a {@code config:*} scope reaches outside
     *  the facade (ConfigResource's export/import) — named here, not
     *  derived, since there are exactly two and they are exactly what
     *  {@code config:export}/{@code config:import} mean by definition. */
    private static boolean reachesConsoleUnderItsOwnScope(ContainerRequestContext ctx, Set<String> scopes) {
        String path = normalizedPath(ctx);
        if (path.equals("api/v1/admin/config/export")) return scopes.contains(ApiKeyScope.CONFIG_EXPORT);
        if (path.equals("api/v1/admin/config/import")) return scopes.contains(ApiKeyScope.CONFIG_IMPORT);
        return false;
    }
}
