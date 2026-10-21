package de.chriscohnen.islandr.auth;

import java.util.Set;

/**
 * Flattened auth view derived from the {@link Session} for one request.
 *
 * The local ENV-bootstrap admin has {@code userId == null} and is always admin.
 * Org users carry their {@code users.is_admin} flag, resolved at filter time
 * (one extra row lookup per request — cheap, and avoids stale per-session state
 * when an admin promotes/demotes a user mid-session).
 *
 * <p>{@code apiKeyScopes} is empty for every session-based auth (a session
 * admin already passed {@code Auth.requireAdmin}, full stop — scopes are an
 * API-key-only concept, apikey-scopes) and holds the key's granted scopes
 * (see {@link de.chriscohnen.islandr.apikey.ApiKeyScope#CATALOG}) when
 * {@code provider} is {@code "apikey"}.
 *
 * <p>{@code edgeHubIds} is the flattened union of every edge hub id any of
 * this key's scopes are bound to (edge-psk-exposure) — not per-scope. A key
 * holding two different edge-bound scopes for two different edges would
 * read as "may use either scope for either edge", which is wrong but, as of
 * the scopes that exist today, unreachable: {@code ApiKeyService#create}
 * only ever binds a single edge per key. Revisit this flattening the day a
 * key legitimately needs two different edge-bound scopes for two different
 * edges.
 */
public record AuthContext(String principal, String userId, String provider, boolean isAdmin,
                           Set<String> apiKeyScopes, Set<String> edgeHubIds) {

    public boolean isLocalAdmin() {
        return Session.LOCAL.equals(provider) && userId == null;
    }

    public boolean isApiKey() {
        return "apikey".equals(provider);
    }

    /** True when this auth may use {@code scope} — always true for a
     *  session (scopes don't apply to a human), and for an API key, true
     *  when it holds {@code "full"} (see
     *  {@code ApiKeyScope#FULL} — not referenced directly, to avoid this
     *  package depending on {@code apikey}) or {@code scope} itself. */
    public boolean hasScope(String scope) {
        return !isApiKey() || apiKeyScopes.contains("full") || apiKeyScopes.contains(scope);
    }

    /** True when this auth may use {@code scope} specifically for
     *  {@code edgeHubId} — always true for a session (same reasoning as
     *  {@link #hasScope}), true for an API key holding {@code "full"}, and
     *  otherwise true only when it holds {@code scope} *and* that scope's
     *  binding includes this edge. Doesn't itself know which scopes are
     *  edge-bound — that's {@code ApiKeyScope#EDGE_BOUND_SCOPES}'s job; a
     *  caller checking a non-edge-bound scope with this method just gets
     *  the same answer {@link #hasScope} would. */
    public boolean hasScopeForEdge(String scope, String edgeHubId) {
        if (!isApiKey()) return true;
        if (apiKeyScopes.contains("full")) return true;
        return apiKeyScopes.contains(scope) && edgeHubIds.contains(edgeHubId);
    }
}
