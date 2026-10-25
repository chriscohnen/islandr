package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.AclResolutionService;
import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.peer.ActivityPoller;
import de.chriscohnen.islandr.peer.Peer;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * External facade for an edge hub to read its own data (ADR-I-0001,
 * ADR-I-0002) — {@code /api/external/v1/edge}, distinct from
 * {@code /api/v1/edge-hubs} (the admin console's CRUD, {@code acl.EdgeHubResource}).
 *
 * <p>Every endpoint here is bound to one edge via its path segment and
 * requires the calling key hold the matching scope *for that edge*
 * ({@code Auth.requireEdgeScope}, not plain {@code requireScope}) — an edge
 * key reads only its own data, never another edge's, by construction rather
 * than by a filter that could be gotten wrong at a call site.
 */
@Path("/api/external/v1/edge")
@Produces(MediaType.APPLICATION_JSON)
public class EdgeExternalResource {

    @Inject AclResolutionService acl;
    @Inject AuditService audit;
    @Inject ActivityPoller activityPoller;

    /**
     * Preshared keys of every peer that may reach this edge (edge-psk-exposure) —
     * the hard blocker for an edge actually bringing a tunnel up for a peer
     * that has one. Deliberately its own endpoint rather than folded into a
     * future plan endpoint: PSK exposure is a materially more sensitive
     * read than the rest of a plan (peer list, rendered ruleset) and gets
     * its own scope ({@code psks:read}) for exactly that reason — bundling
     * it in would force every plan-reader to also be a PSK-reader.
     */
    @GET
    @Path("/{id}/psks")
    public EdgeHubDto.PskResponse psks(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireEdgeScope(ctx, ApiKeyScope.PSKS_READ, id);
        EdgeHub edge = EdgeHub.findById(id);
        if (edge == null) throw new NotFoundException("edge hub not found: " + id);

        List<EdgeHubDto.PeerPsk> peers = Peer.<Peer>listAll().stream()
                .filter(p -> p.presharedKey != null)
                .filter(p -> acl.reachableEdgeHubIdsForPeer(p).contains(id))
                .map(p -> new EdgeHubDto.PeerPsk(p.id, p.name, p.presharedKey))
                .toList();

        audit.logEvent(a.principal(), "edge.psks_read", "EdgeHub:" + edge.name + " (" + id + ")",
                Map.of("peerCount", peers.size()));
        return new EdgeHubDto.PskResponse(peers);
    }

    /**
     * Connectivity/traffic for the peers an edge has locally connected
     * (edge-status-report) — the only way those peers' dashboard status
     * ever leaves "Disconnected", since they never appear in the central
     * hub's own {@code wg show dump}.
     *
     * <p>Authorization happens twice, at two different grains: once for the
     * whole call ({@code telemetry:write} bound to this edge), and once per
     * reported peer (is this public key allowed to reach anything here?). A
     * key legitimately holding the scope can still misreport — accidentally
     * (stale config) or not (a compromised edge claiming someone else's
     * peer) — and {@code lastSeenAt} is not purely cosmetic: the peer
     * scheduler reads it too, so a false "connected" could hold an access
     * window open past when it should have closed. Every rejected public
     * key is refused and audited, never silently applied or silently
     * dropped.
     */
    @POST
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public EdgeHubDto.StatusResponse status(@Context ContainerRequestContext ctx,
                                            @PathParam("id") String id,
                                            @Valid EdgeHubDto.StatusRequest body) {
        AuthContext a = Auth.requireEdgeScope(ctx, ApiKeyScope.TELEMETRY_WRITE, id);
        EdgeHub edge = EdgeHub.findById(id);
        if (edge == null) throw new NotFoundException("edge hub not found: " + id);

        Instant now = Instant.now();
        int accepted = 0;
        List<String> rejected = new ArrayList<>();
        for (EdgeHubDto.PeerSample sample : body.peers()) {
            Peer p = Peer.find("publicKey", sample.publicKey()).firstResult();
            if (p == null || !acl.reachableEdgeHubIdsForPeer(p).contains(id)) {
                rejected.add(sample.publicKey());
                continue;
            }
            if (activityPoller.applySample(p, sample.lastHandshake(), sample.endpoint(),
                    sample.rxBytes(), sample.txBytes(), now)) {
                accepted++;
            }
        }
        if (!rejected.isEmpty()) {
            audit.logEvent(a.principal(), "edge.status_report_rejected", "EdgeHub:" + edge.name + " (" + id + ")",
                    Map.of("rejectedPublicKeys", rejected));
        }
        if (accepted > 0) activityPoller.detectConnectionTransitionsNow();
        return new EdgeHubDto.StatusResponse(accepted, rejected);
    }
}
