package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.identity.OidcLoginService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Browser-facing OIDC endpoints. Two routes:
 * <ul>
 *   <li>{@code GET /api/v1/auth/oidc/{provider}/start} — redirects to the IdP authorize endpoint,
 *       sets the state cookie.</li>
 *   <li>{@code GET /api/v1/auth/oidc/{provider}/callback} — IdP redirects here with ?code&state;
 *       we exchange the code, create the session, set the session cookie, and redirect to /.</li>
 * </ul>
 * The state cookie is short-lived (5 minutes) and read+cleared on callback.
 */
@Path("/api/v1/auth/oidc")
public class OidcAuthResource {

    private static final String STATE_COOKIE_PREFIX = "islandr_oidc_state_";
    private static final int STATE_TTL_SECONDS = 300;

    @Inject OidcLoginService oidc;

    @GET
    @Path("/{provider}/start")
    public Response start(@PathParam("provider") String provider, @Context UriInfo uriInfo) {
        String redirectUri = absoluteCallbackUri(uriInfo, provider);
        OidcLoginService.AuthorizeRedirect a = oidc.buildAuthorizeUrl(provider, redirectUri);
        return Response.seeOther(URI.create(a.url()))
                .cookie(buildStateCookie(provider, a.state(), STATE_TTL_SECONDS))
                .build();
    }

    @GET
    @Path("/{provider}/callback")
    public Response callback(@PathParam("provider") String provider,
                             @QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String idpError,
                             @QueryParam("error_description") String idpErrorDescription,
                             @Context UriInfo uriInfo,
                             @Context jakarta.ws.rs.container.ContainerRequestContext ctx) {
        if (idpError != null) {
            // IdP told us "no" — bounce back to login with a readable message.
            return loginErrorRedirect("idp_" + idpError, idpErrorDescription);
        }
        Cookie stateCookie = ctx.getCookies().get(STATE_COOKIE_PREFIX + provider);
        String cookieState = stateCookie == null ? null : stateCookie.getValue();
        String redirectUri = absoluteCallbackUri(uriInfo, provider);

        try {
            Session s = oidc.handleCallback(provider, code, state, cookieState, redirectUri);
            int maxAge = (int) Duration.between(Instant.now(), s.expiresAt).getSeconds();
            return Response.seeOther(URI.create("/"))
                    .cookie(buildSessionCookie(s.id, maxAge))
                    .cookie(buildStateCookie(provider, "", 0))  // clear state cookie
                    .build();
        } catch (jakarta.ws.rs.BadRequestException bre) {
            return loginErrorRedirect("bad_request", bre.getMessage());
        }
    }

    private static String absoluteCallbackUri(UriInfo uriInfo, String provider) {
        // Hard-code the /api/v1 prefix because Quarkus' baseUri is just the
        // server root — the /api/v1 lives in each @Path annotation, not in an
        // application path. uriInfo.getBaseUri() would give us http://host/,
        // so we have to rebuild the full callback path ourselves. This MUST
        // byte-match the redirect URI registered in the OIDC provider console,
        // or Google/Microsoft reject with redirect_uri_mismatch.
        return uriInfo.getBaseUriBuilder()
                .path("api").path("v1").path("auth").path("oidc").path(provider).path("callback")
                .build().toString();
    }

    private static Response loginErrorRedirect(String code, String detail) {
        String d = detail == null ? "" : detail;
        URI to = URI.create("/login?error=" + urlEncode(code) + "&detail=" + urlEncode(d));
        return Response.seeOther(to).build();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private NewCookie buildStateCookie(String provider, String value, int maxAge) {
        return new NewCookie.Builder(STATE_COOKIE_PREFIX + provider)
                .value(value)
                .path("/api/v1/auth/oidc")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAge)
                .build();
    }

    private NewCookie buildSessionCookie(String value, int maxAge) {
        return new NewCookie.Builder(SessionFilter.COOKIE_NAME)
                .value(value)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAge)
                .build();
    }
}
