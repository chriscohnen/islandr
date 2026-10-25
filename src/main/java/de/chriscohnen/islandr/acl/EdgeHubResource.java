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

@Path("/api/v1/edge-hubs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EdgeHubResource {

    @Inject EdgeHubService edgeHubs;
    @Inject AuditService audit;

    @GET
    public List<EdgeHubDto.Response> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, Long> counts = edgeHubs.siteCountByEdgeHub();
        return edgeHubs.listAll().stream().map(e -> edgeHubs.toResponse(e, counts)).toList();
    }

    @GET
    @Path("/{id}")
    public EdgeHubDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        EdgeHub e = edgeHubs.get(id);
        return edgeHubs.toResponse(e, edgeHubs.siteCountByEdgeHub());
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx, @Valid EdgeHubDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        EdgeHub e = edgeHubs.create(body);
        audit.logCreate(a.principal(), "edge_hub.create", "EdgeHub:" + e.name + " (" + e.id + ")",
                Map.of("name", e.name, "enabled", e.enabled, "lat", e.lat, "lng", e.lng));
        return Response.created(UriBuilder.fromResource(EdgeHubResource.class).path(e.id).build())
                .entity(edgeHubs.toResponse(e, Map.of()))
                .build();
    }

    @PUT
    @Path("/{id}")
    public EdgeHubDto.Response update(@Context ContainerRequestContext ctx,
                                      @PathParam("id") String id,
                                      @Valid EdgeHubDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        EdgeHub before = edgeHubs.get(id);
        Map<String, Object> beforeMap = Map.of(
                "name", before.name, "enabled", before.enabled, "lat", before.lat, "lng", before.lng);
        EdgeHub after = edgeHubs.update(id, body);
        audit.logUpdate(a.principal(), "edge_hub.update", "EdgeHub:" + after.name + " (" + id + ")", beforeMap,
                Map.of("name", after.name, "enabled", after.enabled, "lat", after.lat, "lng", after.lng));
        return edgeHubs.toResponse(after, edgeHubs.siteCountByEdgeHub());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        EdgeHub before = edgeHubs.get(id);
        Map<String, Object> beforeMap = Map.of("name", before.name, "enabled", before.enabled);
        edgeHubs.delete(id);
        audit.logDelete(a.principal(), "edge_hub.delete", "EdgeHub:" + before.name + " (" + id + ")", beforeMap);
        return Response.noContent().build();
    }
}
