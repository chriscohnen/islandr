package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
 * Role grants scoped to a whole site network — "this role's peers reach
 * every host in the Homeoffice subnet" — separate from the per-resource
 * matrix (AclMatrixResource) and from type-grants (AclTypeGrantResource):
 * neither references a single resource. Always full-reach — see
 * RoleNetworkGrant and ADR-0029.
 */
@Path("/api/v1/acl/network-grants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AclNetworkGrantResource {

    @Inject RoleService roles;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @GET
    public List<RoleDto.NetworkGrantResponse> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return roles.listNetworkGrants();
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx, @Valid RoleDto.NetworkGrantRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        RoleNetworkGrant g = roles.createNetworkGrant(body);
        Role role = Role.findById(g.roleId);
        Site site = Site.findById(g.siteId);
        String roleName = role == null ? g.roleId : role.name;
        String siteName = site == null ? g.siteId : site.name;
        audit.logEvent(a.principal(), "grant.network.create",
                "NetworkGrant:" + roleName + "/" + siteName,
                Map.of("role", roleName, "site", siteName));
        rulesets.recomputeFromHook();
        RoleDto.NetworkGrantResponse dto = new RoleDto.NetworkGrantResponse(
                g.id, g.roleId, g.siteId, siteName, g.createdAt);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        RoleNetworkGrant g = RoleNetworkGrant.findById(id);
        String roleName = null, siteName = null;
        if (g != null) {
            Role role = Role.findById(g.roleId);
            Site site = Site.findById(g.siteId);
            roleName = role == null ? g.roleId : role.name;
            siteName = site == null ? g.siteId : site.name;
        }
        roles.deleteNetworkGrant(id);
        audit.logEvent(a.principal(), "grant.network.delete",
                "NetworkGrant:" + (roleName != null ? roleName + "/" + siteName : id),
                roleName != null ? Map.of("role", roleName, "site", siteName) : Map.of());
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }
}
