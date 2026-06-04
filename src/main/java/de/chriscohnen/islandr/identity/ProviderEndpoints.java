package de.chriscohnen.islandr.identity;

/**
 * Hardcoded OIDC discovery endpoints for the two supported providers.
 * Google has a single discovery document; Microsoft is parameterised by tenant.
 * We don't fetch the discovery JSON at runtime — these endpoints are stable
 * enough that hardcoding them is simpler and removes one external dependency
 * from every login (and one cache to invalidate).
 */
public final class ProviderEndpoints {

    public record Endpoints(String authorize, String token, String jwks, String issuer, String userInfo) {}

    public static Endpoints forProvider(OidcProvider p) {
        if (p.isMicrosoft()) {
            String tid = p.tenantId;
            return new Endpoints(
                    "https://login.microsoftonline.com/" + tid + "/oauth2/v2.0/authorize",
                    "https://login.microsoftonline.com/" + tid + "/oauth2/v2.0/token",
                    "https://login.microsoftonline.com/" + tid + "/discovery/v2.0/keys",
                    "https://login.microsoftonline.com/" + tid + "/v2.0",
                    "https://graph.microsoft.com/v1.0/me"
            );
        }
        // Google. Userinfo endpoint exists and is used as a fallback when
        // the ID-Token does NOT carry the `picture` claim — that happens on
        // some Workspace setups where Google only puts essential claims in
        // the token and exposes the rest via userinfo.
        return new Endpoints(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/oauth2/v3/certs",
                "https://accounts.google.com",
                "https://openidconnect.googleapis.com/v1/userinfo"
        );
    }

    public static String scopesFor(OidcProvider p) {
        if (p.isMicrosoft()) {
            // offline_access is harmless without a refresh-token flow; we ask now so we can add later.
            return "openid profile email User.Read offline_access";
        }
        return "openid profile email";
    }

    private ProviderEndpoints() {}
}
