package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Nested-under-site routes for listing and creating resources scoped to a
 * single site. Direct get/update/delete + port operations live under
 * {@link ResourceResource} at /api/v1/resources.
 */
@Path("/api/v1/sites/{siteId}/resources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SiteResourceResource {

    @Inject ResourceService resources;
    @Inject AuditService audit;

    @GET
    public List<ResourceDto.Response> list(@Context ContainerRequestContext ctx,
                                           @PathParam("siteId") String siteId) {
        Auth.requireAdmin(ctx);
        Map<String, List<ResourcePort>> ports = resources.portsByResource();
        return resources.listForSite(siteId).stream()
                .map(r -> ResourceDto.Response.from(r,
                        ports.getOrDefault(r.id, List.of()).stream()
                                .map(ResourceDto.PortResponse::from).toList()))
                .toList();
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx,
                           @PathParam("siteId") String siteId,
                           @Valid ResourceDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource r = resources.create(siteId, body);
        audit.logCreate(a.principal(), "resource.create", "Resource:" + r.name + " (" + r.id + ")", Map.of(
                "siteId", siteId,
                "name", r.name,
                "ip", r.ip,
                "description", r.description == null ? "" : r.description));
        return Response.status(Response.Status.CREATED)
                .entity(ResourceDto.Response.from(r, List.of()))
                .build();
    }
}
