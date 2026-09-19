package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.audit.AuditService;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Security keys for the local recovery admin (ADR-0028, issue #67).
 *
 * <p>Two ceremonies, two calls each. Enrolling requires an existing admin
 * session — you add a second factor to an account you are already holding.
 * Signing in does not, because that is the point of it.
 *
 * <p>A successful assertion ends in {@link SessionService}, the same row and
 * the same {@code islandr_session} cookie every other login produces. The
 * alternative — a second authenticated-session mechanism alongside
 * {@code SessionFilter} — is what ADR-0028 rejected the Quarkus extension for:
 * it would not re-read {@code is_admin} and would not inherit the per-request
 * access check from #53.
 */
@Path("/api/v1/auth/webauthn")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebAuthnResource {

    @Inject WebAuthnService webAuthn;
    @Inject WebAuthnCredentialStore store;
    @Inject SessionService sessions;
    @Inject AdminBootstrap adminBootstrap;
    @Inject AuthResource auth;
    @Inject AuditService audit;

    public record RegisterStart(String label) {}
    public record VerifyRequest(String label, JsonObject response) {}
    public record CredentialView(String id, String label, String rpId,
                                 Instant createdAt, Instant lastUsedAt) {}
    /** What the login screen needs before it decides whether to offer a key:
     *  {@code usableHere} false with {@code registered} true means a key exists
     *  but belongs to another name — worth saying, because the browser will not. */
    public record Availability(boolean hostSupportsKeys, boolean registered, boolean usableHere) {}

    @GET
    @Path("/availability")
    public Availability availability(@HeaderParam("Host") String host) {
        String rpId = RelyingPartyId.of(host);
        return new Availability(
                rpId != null,
                store.hasAny(WebAuthnCredential.LOCAL_ADMIN),
                rpId != null && store.hasUsableFrom(WebAuthnCredential.LOCAL_ADMIN, rpId));
    }

    @POST
    @Path("/register/challenge")
    public JsonObject registerChallenge(@Context ContainerRequestContext ctx,
                                        @HeaderParam("Host") String host,
                                        RegisterStart body) {
        Auth.requireAdmin(ctx);
        return webAuthn.registerChallenge(RelyingPartyId.of(host),
                body == null ? null : body.label());
    }

    @POST
    @Path("/register/verify")
    public Response registerVerify(@Context ContainerRequestContext ctx,
                                   @HeaderParam("Host") String host,
                                   @HeaderParam("Origin") String origin,
                                   VerifyRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        requireResponse(body);
        String rpId = RelyingPartyId.of(host);
        String credentialId = webAuthn.verify(rpId, origin, body.response(), body.label(), true);
        audit.logCreate(a.principal(), "auth.webauthn_register",
                "WebAuthnCredential:" + credentialId,
                Map.of("rpId", String.valueOf(rpId), "label", body.label() == null ? "" : body.label()));
        return Response.ok(Map.of("registered", true)).build();
    }

    @POST
    @Path("/login/challenge")
    public JsonObject loginChallenge(@HeaderParam("Host") String host) {
        return webAuthn.loginChallenge(RelyingPartyId.of(host));
    }

    @POST
    @Path("/login/verify")
    public Response loginVerify(@HeaderParam("Host") String host,
                                @HeaderParam("Origin") String origin,
                                VerifyRequest body) {
        requireResponse(body);
        String rpId = RelyingPartyId.of(host);
        webAuthn.verify(rpId, origin, body.response(), null, false);

        de.chriscohnen.islandr.user.User adminUser =
                de.chriscohnen.islandr.user.User.find("email", AdminUserBootstrap.ADMIN_EMAIL).firstResult();
        String adminUserId = adminUser != null ? adminUser.id : null;
        Session s = sessions.create(Session.LOCAL, adminBootstrap.userName(), adminUserId);
        audit.logEvent(s.principal, "auth.login_webauthn", "Session:" + s.id,
                Map.of("provider", "webauthn", "rpId", String.valueOf(rpId)));
        return auth.okSession(s, new AuthResource.MeResponse(
                s.principal, s.provider, adminUserId, true, s.expiresAt));
    }

    @GET
    public List<CredentialView> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return store.list(WebAuthnCredential.LOCAL_ADMIN).stream()
                .map(c -> new CredentialView(c.id, c.label, c.rpId, c.createdAt, c.lastUsedAt))
                .toList();
    }

    @DELETE
    @Path("/{id}")
    public Response remove(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        boolean removed = store.remove(WebAuthnCredential.LOCAL_ADMIN, id);
        if (!removed) return Response.status(Response.Status.NOT_FOUND).build();
        audit.logDelete(a.principal(), "auth.webauthn_remove", "WebAuthnCredential:" + id,
                Map.of("id", id));
        return Response.noContent().build();
    }

    private static void requireResponse(VerifyRequest body) {
        if (body == null || body.response() == null) {
            throw new BadRequestException("the authenticator's response is missing");
        }
    }
}
