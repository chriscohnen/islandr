package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Avatar lookup with three-tier resolution (see {@link AvatarService}):
 * cached bytes → Gravatar (if enabled, local-only users) → 404.
 * Requires an active session — avatars are user profile photos and must
 * not be accessible to unauthenticated callers.
 *
 * <p>Since 0.22.0 the bytes can also be uploaded (issue #85), by an admin for
 * anyone or by a user for themselves — the self-service portal is where people
 * already manage their own devices, and an avatar is no more sensitive than
 * that. The image arrives as a raw body rather than multipart: it is one file
 * with no metadata, and multipart would add a parser for nothing.
 */
@Path("/api/v1/users/{id}/avatar")
public class UserAvatarResource {

    @Inject AvatarService svc;
    @Inject AuditService audit;

    @GET
    @Produces({"image/jpeg", "image/png", "image/gif", MediaType.WILDCARD})
    public Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.require(ctx);
        AvatarService.Result r = svc.lookup(id);
        if (r == null) return Response.status(404).build();

        Response.ResponseBuilder ok = Response.ok(r.bytes())
                .type(r.contentType() != null ? r.contentType() : "image/jpeg")
                .cacheControl(privateMaxAge(300));
        if (r.etag() != null) ok.tag(r.etag());
        return ok.build();
    }

    @PUT
    @Consumes({"image/png", "image/jpeg"})
    public Response upload(@Context ContainerRequestContext ctx,
                           @PathParam("id") String id,
                           @HeaderParam("content-type") String contentType,
                           byte[] body) {
        AuthContext a = requireSelfOrAdmin(ctx, id);
        String etag = svc.store(id, body, contentType);
        audit.logEvent(a.principal(), "user.avatar_set", "User:" + id,
                Map.of("bytes", body == null ? 0 : body.length,
                       "self", id.equals(a.userId())));
        return Response.noContent().tag(etag).build();
    }

    @DELETE
    public Response remove(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = requireSelfOrAdmin(ctx, id);
        svc.clear(id);
        audit.logEvent(a.principal(), "user.avatar_clear", "User:" + id,
                Map.of("self", id.equals(a.userId())));
        return Response.noContent().build();
    }

    /**
     * An admin may set anyone's avatar; everyone else only their own. The
     * ENV-bootstrapped local admin has no userId of its own, and is an admin,
     * so it passes on the first branch.
     */
    private static AuthContext requireSelfOrAdmin(ContainerRequestContext ctx, String id) {
        AuthContext a = Auth.require(ctx);
        if (a.isAdmin() || id.equals(a.userId())) return a;
        throw new ForbiddenException("you may only change your own avatar");
    }

    private static jakarta.ws.rs.core.CacheControl privateMaxAge(int seconds) {
        jakarta.ws.rs.core.CacheControl cc = new jakarta.ws.rs.core.CacheControl();
        cc.setPrivate(true);
        cc.setMaxAge(seconds);
        return cc;
    }
}
