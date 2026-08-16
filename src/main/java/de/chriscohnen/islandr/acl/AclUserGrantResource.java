package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/v1/acl/user-grants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AclUserGrantResource {

    @Inject UserGrantService grants;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    /** Direct user grants (ADR-0024) for the ACL page's list — same data the Atlas
     * view's drag-and-drop produces, so admins can manage it without the map. */
    @GET
    public List<UserGrantDto.ListItem> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return grants.list();
    }

    @PUT
    public Response apply(@Context ContainerRequestContext ctx, @Valid UserGrantDto.Update body) {
        AuthContext a = Auth.requireAdmin(ctx);
        UserGrantService.GrantDiff diff = grants.apply(body);
        if (diff != null) {
            User user = User.findById(body.userId());
            Resource resource = Resource.findById(body.resourceId());
            String userName = user == null ? body.userId() : user.name;
            String resourceName = resource == null ? body.resourceId() : resource.name;
            audit.logEvent(a.principal(), "user_grant." + diff.change(),
                    "UserGrant:" + userName + "/" + resourceName,
                    Map.of("allPorts", diff.toAllPorts() == null ? diff.fromAllPorts() : diff.toAllPorts()));
            rulesets.recomputeFromHook();
        }
        return Response.ok(Map.of("changed", diff != null ? 1 : 0)).build();
    }
}
