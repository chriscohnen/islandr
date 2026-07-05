package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.identity.OidcProvider;
import de.chriscohnen.islandr.identity.OidcProviderService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject AdminBootstrap adminBootstrap;
    @Inject SessionService sessions;
    @Inject OidcProviderService providers;
    @Inject AuditService audit;
    @Inject de.chriscohnen.islandr.crypto.PasswordHasher passwordHasher;

    private volatile String dummyHash;

    @RegisterForReflection
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @RegisterForReflection
    public record MeResponse(String principal, String provider, String userId, boolean isAdmin, Instant expiresAt) {}
    /**
     * Public projection of an OIDC provider — only the data the unauthenticated
     * login page needs to render the right button. Credentials, tenant, allowed
     * domains all stay behind the admin endpoint in {@link de.chriscohnen.islandr.identity.OidcProviderResource}.
     */
    @RegisterForReflection
    public record PublicProvider(String providerKey, boolean enabled) {
        static PublicProvider from(OidcProvider p) {
            return new PublicProvider(p.providerKey, p.enabled);
        }
    }

    /**
     * Lists which OIDC providers are enabled. Intentionally unauthenticated:
     * the login page must call this before any session exists. Returns only
     * {@code providerKey} + {@code enabled} — same info the OAuth redirect
     * would leak anyway.
     */
    @GET
    @Path("/providers")
    public List<PublicProvider> listProviders() {
        return providers.listAll().stream().map(PublicProvider::from).toList();
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest body) {
        if (body == null || body.username() == null || body.password() == null) {
            return Response.status(400).build();
        }
        // 1. ENV bootstrap admin (in-memory credential), bound to its admin@local
        //    identity (F-01b) so it can own peers and self-assign roles.
        if (adminBootstrap.isEnabled() && adminBootstrap.matches(body.username(), body.password())) {
            de.chriscohnen.islandr.user.User adminUser =
                    de.chriscohnen.islandr.user.User.find("email", AdminUserBootstrap.ADMIN_EMAIL).firstResult();
            String adminUserId = adminUser != null ? adminUser.id : null;
            Session s = sessions.create(Session.LOCAL, adminBootstrap.userName(), adminUserId);
            audit.logEvent(s.principal, "auth.login_local", "Session:" + s.id,
                    java.util.Map.of("provider", "local"));
            return okSession(s, new MeResponse(s.principal, s.provider, adminUserId, true, s.expiresAt));
        }

        // 2. Local DB user with a password (F-01a) — works independently of the ENV
        //    admin and of any configured OIDC provider.
        de.chriscohnen.islandr.user.User localUser = findLocalUser(body.username());
        if (verifyLocalPassword(localUser, body.password())) {
            Session s = sessions.create(Session.LOCAL, localUser.email, localUser.id);
            audit.logEvent(s.principal, "auth.login_local", "Session:" + s.id,
                    java.util.Map.of("provider", "local"));
            return okSession(s, new MeResponse(localUser.email, s.provider, localUser.id, localUser.isAdmin, s.expiresAt));
        }

        // 3. Neither matched. Failed logins are an intrusion signal — audit the
        //    attempted username so a brute-force shows up filterable.
        audit.logEvent(body.username(), "auth.login_failed", null,
                java.util.Map.of("provider", "local"));
        return Response.status(401).entity(error("invalid credentials")).build();
    }

    private Response okSession(Session s, MeResponse me) {
        return Response.ok(me)
                .cookie(buildCookie(s.id, (int) java.time.Duration.between(Instant.now(), s.expiresAt).getSeconds()))
                .build();
    }

    /** Match a local user by email, then by name. */
    private de.chriscohnen.islandr.user.User findLocalUser(String username) {
        de.chriscohnen.islandr.user.User byEmail =
                de.chriscohnen.islandr.user.User.find("email", username).firstResult();
        return byEmail != null ? byEmail
                : de.chriscohnen.islandr.user.User.find("name", username).firstResult();
    }

    /**
     * Verify a password against a local user, equalising timing for the
     * not-found / no-password / disabled cases with a dummy PBKDF2 run so a login
     * attempt does not leak which usernames exist (design 2026-07-05 §6).
     */
    private boolean verifyLocalPassword(de.chriscohnen.islandr.user.User user, String password) {
        boolean eligible = user != null && user.enabled && user.passwordHash != null;
        String hash = eligible ? user.passwordHash : dummyHash();
        boolean match = passwordHasher.verify(password, hash);
        return eligible && match;
    }

    private String dummyHash() {
        String d = dummyHash;
        if (d == null) {
            d = passwordHasher.hash("timing-equalizer");
            dummyHash = d;
        }
        return d;
    }

    @POST
    @Path("/logout")
    @Consumes(MediaType.WILDCARD)  // logout has no body; don't reject for missing Content-Type
    public Response logout(@Context ContainerRequestContext ctx) {
        Cookie c = ctx.getCookies().get(SessionFilter.COOKIE_NAME);
        if (c != null) {
            // Resolve before revoking so the principal makes it into the audit
            // row even though we're tearing the session down at the same time.
            Session s = sessions.findActive(c.getValue());
            sessions.revoke(c.getValue());
            if (s != null) {
                audit.logEvent(s.principal, "auth.logout", "Session:" + s.id,
                        java.util.Map.of("provider", s.provider));
            }
        }
        return Response.noContent()
                .cookie(buildCookie("", 0))  // max-age 0 = delete in browser
                .build();
    }

    @GET
    @Path("/me")
    public Response me(@Context ContainerRequestContext ctx) {
        Session s = (Session) ctx.getProperty(SessionFilter.CTX_SESSION);
        if (s == null) return Response.status(401).build();
        AuthContext a = Auth.current(ctx);
        boolean isAdmin = a != null && a.isAdmin();
        return Response.ok(new MeResponse(s.principal, s.provider, s.userId, isAdmin, s.expiresAt)).build();
    }

    private NewCookie buildCookie(String value, int maxAgeSeconds) {
        // Secure flag intentionally left false: dev runs over plain HTTP.
        // The reverse proxy / TLS terminator in prod sets Secure via cookie rewrite
        // (or we promote this to a config flag when deploying).
        return new NewCookie.Builder(SessionFilter.COOKIE_NAME)
                .value(value)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static java.util.Map<String, String> error(String msg) {
        return java.util.Map.of("error", msg);
    }
}
