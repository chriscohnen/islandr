package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.user.User;
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

@Path("/api/v1/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RoleResource {

    @Inject RoleService roles;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @GET
    public List<RoleDto.Response> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, Long> members = roles.memberCounts();
        Map<String, Long> grants = roles.grantCounts();
        return roles.listAll().stream()
                .map(r -> RoleDto.Response.from(r,
                        members.getOrDefault(r.id, 0L).intValue(),
                        grants.getOrDefault(r.id, 0L).intValue()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public RoleDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        Role r = roles.get(id);
        return RoleDto.Response.from(r,
                roles.memberUserIds(id).size(),
                roles.grantCounts().getOrDefault(id, 0L).intValue());
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx,
                           @Valid RoleDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Role r = roles.create(body);
        audit.logCreate(a.principal(), "role.create", "Role:" + r.id,
                Map.of("name", r.name,
                        "description", r.description == null ? "" : r.description));
        return Response.created(UriBuilder.fromResource(RoleResource.class).path(r.id).build())
                .entity(RoleDto.Response.from(r, 0, 0))
                .build();
    }

    @PUT
    @Path("/{id}")
    public RoleDto.Response update(@Context ContainerRequestContext ctx,
                                   @PathParam("id") String id,
                                   @Valid RoleDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Role before = roles.get(id);
        Map<String, Object> beforeMap = Map.of(
                "name", before.name,
                "description", before.description == null ? "" : before.description);
        Role after = roles.update(id, body);
        audit.logUpdate(a.principal(), "role.update", "Role:" + id, beforeMap,
                Map.of("name", after.name,
                        "description", after.description == null ? "" : after.description));
        return RoleDto.Response.from(after,
                roles.memberUserIds(id).size(),
                roles.grantCounts().getOrDefault(id, 0L).intValue());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Role before = roles.get(id);
        Map<String, Object> beforeMap = Map.of("name", before.name);
        roles.delete(id);
        audit.logDelete(a.principal(), "role.delete", "Role:" + id, beforeMap);
        // Cascade kills grants + memberships; rules anchored on this role are gone.
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/users")
    public List<RoleDto.MemberResponse> members(@Context ContainerRequestContext ctx,
                                                @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        roles.get(id);  // 404 if missing
        List<String> ids = roles.memberUserIds(id);
        if (ids.isEmpty()) return List.of();
        return User.<User>list("id in ?1", ids).stream()
                .map(u -> new RoleDto.MemberResponse(u.id, u.name, u.email))
                .toList();
    }

    @PUT
    @Path("/{id}/users")
    public Response setMembers(@Context ContainerRequestContext ctx,
                               @PathParam("id") String id,
                               @Valid RoleDto.MembershipRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        RoleService.MembershipDiff diff = roles.setMembers(
                id, body.userIds() == null ? List.of() : body.userIds());
        for (String userId : diff.added()) {
            audit.logEvent(a.principal(), "role.member_add", "Role:" + id,
                    Map.of("userId", userId));
        }
        for (String userId : diff.removed()) {
            audit.logEvent(a.principal(), "role.member_remove", "Role:" + id,
                    Map.of("userId", userId));
        }
        if (!diff.added().isEmpty() || !diff.removed().isEmpty()) {
            // Membership change means new peers are allowed (or no longer
            // allowed) on the resources this role grants — recompute.
            rulesets.recomputeFromHook();
        }
        return Response.noContent().build();
    }
}
