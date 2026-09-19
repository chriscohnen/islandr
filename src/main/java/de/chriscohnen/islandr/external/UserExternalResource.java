package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.user.UserAccessService;
import de.chriscohnen.islandr.user.UserDto;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** External automation facade for users (issue #15, ADR-0026) —
 *  {@code /api/external/v1/users}. See {@link PeerExternalResource} for the
 *  separate-facade rationale. */
@Path("/api/external/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserExternalResource {

    @Inject UserAccessService access;
    @Inject AuditService audit;

    @GET
    public List<UserDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return User.<User>listAll(Sort.by("createdAt").descending())
                .stream().map(UserDto.Response::from).toList();
    }

    /**
     * Sets a user's {@code enabled} flag. Disabling withdraws network access,
     * not just the login: the user's peers go down with the account, through
     * the same {@link UserAccessService} the admin API and the expiry job use.
     *
     * <p>Re-enabling does not bring those peers back. Some may have been off
     * for unrelated reasons long before the account was locked, so deciding a
     * device may reconnect stays an explicit, per-peer action — see
     * {@code PUT /api/external/v1/peers/{id}/enabled}.
     *
     * <p>Unlike the admin API there is no "cannot disable your own account"
     * guard: the caller is an API key, not a signed-in user, so there is no own
     * account to lock out of.
     */
    @PUT
    @Path("/{id}/enabled")
    @Transactional
    public UserDto.Response setEnabled(@Context ContainerRequestContext ctx,
                                       @PathParam("id") String id,
                                       UserDto.EnabledRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        boolean wanted = body != null && body.enabled();
        if (u.enabled == wanted) return UserDto.Response.from(u);

        u.enabled = wanted;
        String action = wanted ? "user.enable" : "user.disable";
        audit.logUpdate(a.principal(), action, "User:" + u.name + " (" + u.id + ")",
                Map.of("enabled", !wanted), Map.of("enabled", wanted, "via", "external-api"));
        if (!wanted) {
            access.withdrawPeerAccess(u.id);
        }
        return UserDto.Response.from(u);
    }
}
