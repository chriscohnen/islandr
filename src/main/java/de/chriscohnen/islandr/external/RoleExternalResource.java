package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleDto;
import de.chriscohnen.islandr.acl.RoleService;
import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** External automation facade for roles (ADR-0027 precondition) —
 *  {@code /api/external/v1/roles}, read-only in v1. See
 *  {@link PeerExternalResource} for the separate-facade rationale. */
@Path("/api/external/v1/roles")
@Produces(MediaType.APPLICATION_JSON)
public class RoleExternalResource {

    @Inject RoleService roles;

    @GET
    public List<RoleDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, Long> members = roles.memberCounts();
        Map<String, Long> grants = roles.grantCounts();
        return roles.listAll().stream()
                .map((Role r) -> RoleDto.Response.from(r,
                        members.getOrDefault(r.id, 0L).intValue(),
                        grants.getOrDefault(r.id, 0L).intValue()))
                .toList();
    }
}
