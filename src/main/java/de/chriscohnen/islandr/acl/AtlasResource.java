package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.Auth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Admin-only global "who can reach what" graph for the Atlas view — every
 * User, every Resource, and one edge per contributing grant (role,
 * type-grant, or direct user-grant per ADR-0024).
 */
@ApplicationScoped
@Path("/api/v1/acl/atlas")
@Produces(MediaType.APPLICATION_JSON)
public class AtlasResource {

    @Inject AclResolutionService resolution;

    @GET
    public AtlasDto.Graph atlas(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return resolution.buildAtlasGraph();
    }
}
