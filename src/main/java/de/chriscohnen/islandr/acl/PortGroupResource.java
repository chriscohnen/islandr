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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/v1/port-groups")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PortGroupResource {

    @Inject PortGroupService groups;
    @Inject AuditService audit;

    @GET
    public List<PortGroupDto.Response> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, List<PortGroupMember>> byGroup = groups.membersByGroup();
        return groups.listAll().stream()
                .map(g -> PortGroupDto.Response.from(g,
                        byGroup.getOrDefault(g.id, List.of()).stream()
                                .map(m -> new ResourceDto.PortResponse(
                                        m.id, m.port, m.portEnd, m.transport, m.protocol, m.label, null, null))
                                .toList()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public PortGroupDto.Response get(@Context ContainerRequestContext ctx,
                                     @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        PortGroup g = groups.get(id);
        return PortGroupDto.Response.from(g,
                groups.membersFor(id).stream()
                        .map(m -> new ResourceDto.PortResponse(
                                m.id, m.port, m.portEnd, m.transport, m.protocol, m.label, null, null))
                        .toList());
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx,
                           @Valid PortGroupDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        PortGroup g = groups.create(body);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", g.name);
        snapshot.put("description", g.description == null ? "" : g.description);
        snapshot.put("members", groups.memberSnapshot(g.id));
        audit.logCreate(a.principal(), "port_group.create", "PortGroup:" + g.name, snapshot);
        return Response.created(UriBuilder.fromResource(PortGroupResource.class).path(g.id).build())
                .entity(get(ctx, g.id))
                .build();
    }

    @PUT
    @Path("/{id}")
    public PortGroupDto.Response update(@Context ContainerRequestContext ctx,
                                        @PathParam("id") String id,
                                        @Valid PortGroupDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        PortGroup before = groups.get(id);
        Map<String, Object> beforeSnap = new LinkedHashMap<>();
        beforeSnap.put("name", before.name);
        beforeSnap.put("description", before.description == null ? "" : before.description);
        beforeSnap.put("members", groups.memberSnapshot(id));

        groups.update(id, body);

        Map<String, Object> afterSnap = new LinkedHashMap<>();
        afterSnap.put("name", body.name());
        afterSnap.put("description", body.description() == null ? "" : body.description());
        afterSnap.put("members", groups.memberSnapshot(id));

        audit.logUpdate(a.principal(), "port_group.update", "PortGroup:" + body.name(),
                beforeSnap, afterSnap);
        return get(ctx, id);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        PortGroup before = groups.get(id);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", before.name);
        snap.put("members", groups.memberSnapshot(id));
        groups.delete(id);
        audit.logDelete(a.principal(), "port_group.delete", "PortGroup:" + before.name, snap);
        return Response.noContent().build();
    }
}
