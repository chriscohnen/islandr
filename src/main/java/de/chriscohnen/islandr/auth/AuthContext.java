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
 */
public record AuthContext(String principal, String userId, String provider, boolean isAdmin, Set<String> apiKeyScopes) {

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
}
