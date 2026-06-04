package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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

/**
 * Admin-side nested {@code /users/{userId}/peers} endpoints. Self-service
 * (a user managing their own peers) goes through {@code /peers/mine}, not here.
 */
@Path("/api/v1/users/{userId}/peers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserPeerResource {

    @Inject PeerService peers;
    @Inject AuditService audit;

    @GET
    public List<PeerDto.Response> listForUser(@Context ContainerRequestContext ctx,
                                              @PathParam("userId") String userId) {
        Auth.requireAdmin(ctx);
        return Peer.<Peer>list("userId = ?1", Sort.by("createdAt").descending(), userId)
                .stream().map(PeerDto.Response::from).toList();
    }

    @POST
    public Response create(@Context ContainerRequestContext ctx,
                           @PathParam("userId") String userId,
                           @Valid PeerDto.CreateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        PeerDto.CreateResponse out = peers.createForUser(userId, body);
        // nftables recompute happens inside createForUser as part of the
        // try/compensate saga — do not call recomputeFromHook() here again.
        audit.logCreate(a.principal(), "peer.create", "Peer:" + out.peer().id(),
                Map.of(
                        "name", out.peer().name(),
                        "userId", userId,
                        "assignedIp", out.peer().assignedIp(),
                        "type", out.peer().type(),
                        "siteAllowedCidrs", out.peer().siteAllowedCidrs() == null ? "" : out.peer().siteAllowedCidrs(),
                        "publicKey", out.peer().publicKey()));
        return Response
                .created(UriBuilder.fromResource(PeerResource.class)
                        .path("/{id}").build(out.peer().id()))
                .entity(out)
                .build();
    }
}
