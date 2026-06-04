package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
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

@Path("/api/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @GET
    public List<UserDto.Response> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return User.<User>listAll(Sort.by("createdAt").descending())
                .stream()
                .map(UserDto.Response::from)
                .toList();
    }

    @GET
    @Path("/{id}")
    public UserDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) {
            throw new NotFoundException("user not found: " + id);
        }
        return UserDto.Response.from(u);
    }

    @POST
    @Transactional
    public Response create(@Context ContainerRequestContext ctx, @Valid UserDto.CreateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.createNew(body.name(), body.email());
        u.persist();
        audit.logCreate(a.principal(), "user.create", "User:" + u.id,
                Map.of("name", u.name, "email", u.email, "isAdmin", u.isAdmin));
        return Response
                .created(UriBuilder.fromResource(UserResource.class).path(u.id).build())
                .entity(UserDto.Response.from(u))
                .build();
    }

    @PUT
    @Path("/{id}/admin")
    @Transactional
    public UserDto.Response setAdmin(@Context ContainerRequestContext ctx,
                                     @PathParam("id") String id,
                                     UserDto.AdminFlagRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        boolean wanted = body != null && body.isAdmin();
        if (u.isAdmin == wanted) return UserDto.Response.from(u);  // no-op, no audit
        u.isAdmin = wanted;
        // Two distinct actions on the same column so filtering on
        // "show me every privilege escalation" is one WHERE clause, not two.
        String action = wanted ? "user.admin_grant" : "user.admin_revoke";
        audit.logUpdate(a.principal(), action, "User:" + u.id,
                Map.of("isAdmin", !wanted), Map.of("isAdmin", wanted));
        return UserDto.Response.from(u);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", u.name);
        before.put("email", u.email);
        before.put("isAdmin", u.isAdmin);
        u.delete();
        audit.logDelete(a.principal(), "user.delete", "User:" + id, before);
        // FK cascade kills peers and user_roles rows; the user-bound accept
        // rules need to vanish from nftables.
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }
}
