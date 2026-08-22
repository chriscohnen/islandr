package de.chriscohnen.islandr.identity;

/**
 * Unified view of "the one provider a login/callback is happening against" —
 * either a hardcoded {@link OidcProvider} row (Microsoft/Google) or a
 * discovered {@link OidcCustomProvider} row (issue #69). {@link OidcLoginService}
 * and {@link IdTokenVerifier} work off this shape exclusively so neither has
 * to special-case "where did this come from" beyond the {@link #kind()} check
 * Microsoft already needed (issuer-tolerance quirk).
 *
 * <p>{@code key} is the routing/session/persistence key: the literal
 * {@code "microsoft"}/{@code "google"} for the two hardcoded providers, or the
 * custom provider's own row id (a UUID) — this is also exactly the path
 * segment {@code OidcAuthResource}'s already-generic {@code /{provider}/...}
 * routes receive, so no routing changes were needed to add a third kind.
 */
public record ResolvedOidcProvider(
        String key,
        String kind,          // "microsoft" | "google" | "custom"
        boolean enabled,
        String clientId,
        String clientSecret,
        String allowedDomains,
        String scopes,
        ProviderEndpoints.Endpoints endpoints,
        String tenantId       // Microsoft only — null for google/custom
) {
    public boolean isMicrosoft() {
        return OidcProvider.MICROSOFT.equals(kind);
    }

    public boolean isCustom() {
        return "custom".equals(kind);
    }
}
