package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.user.UserDto;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** External automation facade for users (issue #15, ADR-0026) —
 *  {@code /api/external/v1/users}, read-only in v1. See
 *  {@link PeerExternalResource} for the separate-facade rationale. */
@Path("/api/external/v1/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserExternalResource {

    @GET
    public List<UserDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return User.<User>listAll(Sort.by("createdAt").descending())
                .stream().map(UserDto.Response::from).toList();
    }
}
