package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.util.List;
import java.util.Map;

@Path("/api/v1/sites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SiteResource {

    @Inject SiteService sites;
    @Inject AuditService audit;

    @GET
    public List<SiteDto.Response> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, Long> counts = sites.resourceCountBySite();
        return sites.listAll().stream()
                .map(s -> SiteDto.Response.from(s, counts.getOrDefault(s.id, 0L).intValue()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public SiteDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        Site s = sites.get(id);
        return SiteDto.Response.from(s, (int) de.chriscohnen.islandr.acl.Resource.count("siteId", id));
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx,
                           @Valid SiteDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Site s = sites.create(body);
        audit.logCreate(a.principal(), "site.create", "Site:" + s.id,
                Map.of("name", s.name, "cidr", s.cidr,
                        "description", s.description == null ? "" : s.description));
        return Response.created(UriBuilder.fromResource(SiteResource.class).path(s.id).build())
                .entity(SiteDto.Response.from(s, 0))
                .build();
    }

    @PUT
    @Path("/{id}")
    public SiteDto.Response update(@Context ContainerRequestContext ctx,
                                   @PathParam("id") String id,
                                   @Valid SiteDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Site before = sites.get(id);
        Map<String, Object> beforeMap = Map.of(
                "name", before.name, "cidr", before.cidr,
                "description", before.description == null ? "" : before.description);
        Site after = sites.update(id, body);
        audit.logUpdate(a.principal(), "site.update", "Site:" + id, beforeMap,
                Map.of("name", after.name, "cidr", after.cidr,
                        "description", after.description == null ? "" : after.description));
        return SiteDto.Response.from(after, (int) de.chriscohnen.islandr.acl.Resource.count("siteId", id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Site before = sites.get(id);
        Map<String, Object> beforeMap = Map.of(
                "name", before.name, "cidr", before.cidr,
                "description", before.description == null ? "" : before.description);
        sites.delete(id);
        audit.logDelete(a.principal(), "site.delete", "Site:" + id, beforeMap);
        return Response.noContent().build();
    }
}
