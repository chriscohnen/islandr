package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.identity.OidcCustomProvider;
import de.chriscohnen.islandr.identity.OidcCustomProviderService;
import de.chriscohnen.islandr.identity.OidcProvider;
import de.chriscohnen.islandr.identity.OidcProviderService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.http.HttpServerRequest;
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

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    @Inject AdminBootstrap adminBootstrap;
    @Inject SessionService sessions;
    @Inject OidcProviderService providers;
    @Inject OidcCustomProviderService customProviders;
    @Inject AuditService audit;
    @Inject de.chriscohnen.islandr.crypto.PasswordHasher passwordHasher;
    @Inject LoginThrottle throttle;
    @Inject ClientAddress clientAddress;

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
    public record PublicProvider(String providerKey, String kind, String displayName, boolean enabled) {
        static PublicProvider from(OidcProvider p) {
            // kind == providerKey for the two hardcoded ones; displayName is
            // resolved client-side via i18n for these (unchanged behavior),
            // so it's left null here rather than duplicating translated copy.
            return new PublicProvider(p.providerKey, p.providerKey, null, p.enabled);
        }

        static PublicProvider from(OidcCustomProvider p) {
            // Custom providers have no client-side i18n label — displayName
            // is admin-typed and carried through as-is (issue #69).
            return new PublicProvider(p.id, "custom", p.displayName, p.enabled);
        }
    }

    /**
     * Lists which OIDC providers are enabled — the two hardcoded ones and
     * every admin-configured generic one (issue #69). Intentionally
     * unauthenticated: the login page must call this before any session
     * exists. Returns only the fields needed to render a login button —
     * same info the OAuth redirect would leak anyway.
     */
    @GET
    @Path("/providers")
    public List<PublicProvider> listProviders() {
        List<PublicProvider> all = new java.util.ArrayList<>();
        providers.listAll().stream().map(PublicProvider::from).forEach(all::add);
        customProviders.listAll().stream().map(PublicProvider::from).forEach(all::add);
        return all;
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest body, @Context HttpServerRequest request) {
        if (body == null || body.username() == null || body.password() == null) {
            return Response.status(400).build();
        }
        String ip = clientAddress.of(request);

        // The ceiling is taken before anything else: without it the delay
        // below could be sidestepped by firing every guess in parallel.
        if (!throttle.tryEnter()) {
            LOG.warnf("login throttled user=%s ip=%s reason=too-many-in-flight", body.username(), ip);
            return Response.status(429).header("Retry-After", "1")
                    .entity(error("too many login attempts")).build();
        }
        try {
            // Applied before the credential check and keyed on the submitted
            // username whether or not it exists, so the delay cannot become the
            // user-enumeration oracle the dummy PBKDF2 run prevents.
            sleepQuietly(throttle.delayMillis(body.username(), ip));
            return attemptLogin(body, ip);
        } finally {
            throttle.exit();
        }
    }

    private Response attemptLogin(LoginRequest body, String ip) {
        // 1. ENV bootstrap admin (in-memory credential), bound to its admin@local
        //    identity (F-01b) so it can own peers and self-assign roles.
        if (adminBootstrap.isEnabled() && adminBootstrap.matches(body.username(), body.password())) {
            de.chriscohnen.islandr.user.User adminUser =
                    de.chriscohnen.islandr.user.User.find("email", AdminUserBootstrap.ADMIN_EMAIL).firstResult();
            String adminUserId = adminUser != null ? adminUser.id : null;
            Session s = sessions.create(Session.LOCAL, adminBootstrap.userName(), adminUserId);
            throttle.recordSuccess(body.username(), ip);
            audit.logEvent(s.principal, "auth.login_local", "Session:" + s.id,
                    java.util.Map.of("provider", "local", "clientIp", ip));
            return okSession(s, new MeResponse(s.principal, s.provider, adminUserId, true, s.expiresAt));
        }

        // 2. Local DB user with a password (F-01a) — works independently of the ENV
        //    admin and of any configured OIDC provider.
        de.chriscohnen.islandr.user.User localUser = findLocalUser(body.username());
        if (verifyLocalPassword(localUser, body.password())) {
            Session s = sessions.create(Session.LOCAL, localUser.email, localUser.id);
            throttle.recordSuccess(body.username(), ip);
            audit.logEvent(s.principal, "auth.login_local", "Session:" + s.id,
                    java.util.Map.of("provider", "local", "clientIp", ip));
            return okSession(s, new MeResponse(localUser.email, s.provider, localUser.id, localUser.isAdmin, s.expiresAt));
        }

        // 3. Neither matched. Failed logins are an intrusion signal — audit the
        //    attempted username so a brute-force shows up filterable, and write
        //    one line to the application log that an external blocker can match.
        //    The shape below is documented in docs/install/fail2ban.md and is
        //    part of the interface: changing it breaks every deployed jail.
        throttle.recordFailure(body.username(), ip);
        LOG.warnf("login failed user=%s ip=%s", body.username(), ip);
        audit.logEvent(body.username(), "auth.login_failed", null,
                java.util.Map.of("provider", "local", "clientIp", ip));
        return Response.status(401).entity(error("invalid credentials")).build();
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        // accessAllowedAt, not just enabled: an expired access window must
        // refuse the login too (issue #53).
        boolean eligible = user != null && user.accessAllowedAt(java.time.Instant.now())
                && user.passwordHash != null;
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
