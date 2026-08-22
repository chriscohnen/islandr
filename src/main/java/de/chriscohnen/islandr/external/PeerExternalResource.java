package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerDto;
import de.chriscohnen.islandr.peer.PeerService;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * External automation facade for peers (issue #15, ADR-0026) —
 * {@code /api/external/v1/peers}, API-key (or session) authenticated,
 * deliberately separate from the internal {@code /api/v1/peers} the SPA
 * uses. Delegates to the same {@link PeerService} — no duplicated business
 * logic, only a separate, stability-committed URL/DTO surface.
 *
 * <p>v1 scope per ADR-0026: list + create only. Every other peer operation
 * (rotate key, enable/disable, delete, ...) is a candidate for a later,
 * incremental addition to this same pattern, not something this endpoint
 * needs to cover on day one.
 */
@Path("/api/external/v1/peers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PeerExternalResource {

    @Inject PeerService peers;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @GET
    public List<PeerDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return Peer.<Peer>listAll(Sort.by("createdAt").descending())
                .stream().map(PeerDto.Response::from).toList();
    }

    /** @param userId when present, creates a client peer owned by that user
     *         (mirrors {@code POST /api/v1/users/{userId}/peers}); when
     *         absent, {@code body.type} must be {@code "site"} (mirrors
     *         {@code POST /api/v1/peers}) — same two-path split as the
     *         internal API, collapsed into one endpoint since an external
     *         caller has no natural "current user context" to nest under. */
    @POST
    public Response create(@Context ContainerRequestContext ctx,
                           @QueryParam("userId") String userId,
                           @Valid PeerDto.CreateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (userId == null && !"site".equals(body.resolvedType())) {
            throw new jakarta.ws.rs.BadRequestException(
                    "userId is required for client peers; omit it only when type=site");
        }
        PeerDto.CreateResponse out = peers.createForUser(userId, body);
        audit.logCreate(a.principal(), "peer.create", "Peer:" + out.peer().name() + " (" + out.peer().id() + ")",
                Map.of("name", out.peer().name(), "assignedIp", out.peer().assignedIp(),
                        "type", out.peer().type(), "via", "external-api"));
        rulesets.recomputeFromHook();
        return Response.created(URI.create("/api/external/v1/peers/" + out.peer().id())).entity(out).build();
    }
}
