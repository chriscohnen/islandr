package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Avatar lookup with three-tier resolution (see {@link AvatarService}):
 * cached bytes → Gravatar (if enabled, local-only users) → 404.
 * Requires an active session — avatars are user profile photos and must
 * not be accessible to unauthenticated callers.
 */
@Path("/api/v1/users/{id}/avatar")
public class UserAvatarResource {

    @Inject AvatarService svc;

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

    private static jakarta.ws.rs.core.CacheControl privateMaxAge(int seconds) {
        jakarta.ws.rs.core.CacheControl cc = new jakarta.ws.rs.core.CacheControl();
        cc.setPrivate(true);
        cc.setMaxAge(seconds);
        return cc;
    }
}
