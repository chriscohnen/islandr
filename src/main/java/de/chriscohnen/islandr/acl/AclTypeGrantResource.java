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
 * Role grants scoped by resource type within a site — "all printers in
 * Homeoffice" — separate from the per-resource matrix (AclMatrixResource)
 * since a type-grant isn't a matrix cell: it doesn't reference one concrete
 * resource, and applies automatically to resources of that type created
 * later too. Additive-only, always all-ports — see RoleResourceTypeGrant.
 */
@Path("/api/v1/acl/type-grants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AclTypeGrantResource {

    @Inject RoleService roles;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @GET
    public List<RoleDto.TypeGrantResponse> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return roles.listTypeGrants();
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx, @Valid RoleDto.TypeGrantRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        RoleResourceTypeGrant g = roles.createTypeGrant(body);
        Role role = Role.findById(g.roleId);
        Site site = Site.findById(g.siteId);
        String roleName = role == null ? g.roleId : role.name;
        String siteName = site == null ? g.siteId : site.name;
        audit.logEvent(a.principal(), "grant.type.create",
                "TypeGrant:" + roleName + "/" + siteName + "/" + g.resourceType,
                Map.of("role", roleName, "site", siteName, "resourceType", g.resourceType));
        rulesets.recomputeFromHook();
        RoleDto.TypeGrantResponse dto = new RoleDto.TypeGrantResponse(
                g.id, g.roleId, g.siteId, siteName, g.resourceType, g.createdAt);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        // Resolve names before deleting — the grant row itself (and its
        // role_id/site_id) is gone once deleteTypeGrant returns.
        RoleResourceTypeGrant g = RoleResourceTypeGrant.findById(id);
        String roleName = null, siteName = null, resourceType = null;
        if (g != null) {
            Role role = Role.findById(g.roleId);
            Site site = Site.findById(g.siteId);
            roleName = role == null ? g.roleId : role.name;
            siteName = site == null ? g.siteId : site.name;
            resourceType = g.resourceType;
        }
        roles.deleteTypeGrant(id);
        audit.logEvent(a.principal(), "grant.type.delete",
                "TypeGrant:" + (roleName != null ? roleName + "/" + siteName + "/" + resourceType : id),
                roleName != null ? Map.of("role", roleName, "site", siteName, "resourceType", resourceType) : Map.of());
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }
}
