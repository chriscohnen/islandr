package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.Auth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Admin-only "what can this user reach" graph for the Atlas view — a
 * directed-graph shape of the same underlying grant data MyAccessResource
 * exposes as a flat list, plus every resource in the relevant site(s) so
 * unreachable siblings can be drag-to-grant targets.
 */
@ApplicationScoped
@Path("/api/v1/acl/atlas")
@Produces(MediaType.APPLICATION_JSON)
public class AtlasResource {

    @Inject AclResolutionService resolution;

    @GET
    @Path("/{userId}")
    public AtlasDto.Graph atlas(
            @Context ContainerRequestContext ctx,
            @PathParam("userId") String userId) {
        Auth.requireAdmin(ctx);
        return resolution.buildAtlasGraph(userId);
    }
}
