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
                .filter(p -> servedBy(p, id))
                .map(p -> new EdgeHubDto.PeerPsk(p.id, p.name, p.presharedKey))
                .toList();

        audit.logEvent(a.principal(), "edge.psks_read", "EdgeHub:" + edge.name + " (" + id + ")",
                Map.of("peerCount", peers.size()));
        return new EdgeHubDto.PskResponse(peers);
    }

    /**
     * Whether this edge serves the peer: it dials in here ({@code Peer.edgeHubId}, ADR-I-0003 — where
     * a device connects), or its access reaches a resource behind this edge. The first is what the plan
     * lists, so status and preshared keys must follow it; a peer attached to an edge but with no grant
     * there yet would otherwise be in the plan and refused in the status report.
     */
    private boolean servedBy(Peer p, String edgeId) {
        return edgeId.equals(p.edgeHubId) || acl.reachableEdgeHubIdsForPeer(p).contains(edgeId);
    }

    /**
     * The peers an edge is to serve (edge-plan-peers, ADR-I-0005): those attached
     * to it ({@code peers.edge_hub_id}, ADR-I-0003), enabled, with public key and
     * tunnel addresses. No preshared keys — {@code hasPresharedKey} only; the keys
     * stay behind {@code psks:read}. The serial rises exactly when this list
     * changes, so an edge can tell "nothing new" from "apply again".
     */
    @GET
    @Path("/{id}/plan")
    @Transactional
    public EdgeHubDto.PlanResponse plan(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireEdgeScope(ctx, ApiKeyScope.PLAN_READ, id);
        EdgeHub edge = EdgeHub.findById(id);
        if (edge == null) throw new NotFoundException("edge hub not found: " + id);

        List<EdgeHubDto.PlanPeer> peers = Peer.<Peer>list("edgeHubId = ?1 and enabled = true", id).stream()
                .sorted(java.util.Comparator.comparing(p -> p.id))
                .map(p -> {
                    List<String> ips = new ArrayList<>();
                    if (p.assignedIp != null && !p.assignedIp.isBlank()) ips.add(p.assignedIp + "/32");
                    if (p.assignedIpv6 != null && !p.assignedIpv6.isBlank()) ips.add(p.assignedIpv6 + "/128");
                    return new EdgeHubDto.PlanPeer(p.id, p.name, p.publicKey, ips,
                            p.presharedKey != null && !p.presharedKey.isBlank());
                })
                .toList();

        String hash = contentHash(peers);
        if (!hash.equals(edge.planHash)) {
            edge.planHash = hash;
            edge.planSerial++;
        }
        audit.logEvent(a.principal(), "edge.plan_read", "EdgeHub:" + edge.name + " (" + id + ")",
                Map.of("serial", edge.planSerial, "peerCount", peers.size()));
        return new EdgeHubDto.PlanResponse(1, edge.planSerial, Instant.now(),
                new EdgeHubDto.PlanEdge(edge.id, edge.name), peers);
    }

    private static String contentHash(List<EdgeHubDto.PlanPeer> peers) {
        StringBuilder sb = new StringBuilder();
        for (EdgeHubDto.PlanPeer p : peers) {
            sb.append(p.id()).append('|').append(p.name()).append('|').append(p.publicKey()).append('|')
                    .append(String.join(",", p.allowedIps())).append('|').append(p.hasPresharedKey()).append('\n');
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        edge.lastSyncAt = now;
        if (body.planSerial() != null) {
            edge.lastPlanSerial = body.planSerial();
            edge.lastAppliedAt = body.appliedAt();
            edge.planRejected = body.planRejected() == null || body.planRejected().isBlank() ? null : body.planRejected();
            edge.skippedPeers = body.skippedPeers();
        }
        if (body.agentVersion() != null && !body.agentVersion().isBlank()) edge.agentVersion = body.agentVersion();
        int accepted = 0;
        List<String> rejected = new ArrayList<>();
        for (EdgeHubDto.PeerSample sample : body.peers() == null ? List.<EdgeHubDto.PeerSample>of() : body.peers()) {
            Peer p = Peer.find("publicKey", sample.publicKey()).firstResult();
            if (p == null || !servedBy(p, id)) {
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
