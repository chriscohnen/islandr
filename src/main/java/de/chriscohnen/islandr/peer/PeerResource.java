package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/v1/peers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PeerResource {

    @Inject PeerService peers;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;
    @Inject WgAdapter wg;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    @GET
    public List<PeerDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return Peer.<Peer>listAll(Sort.by("createdAt").descending())
                .stream().map(PeerDto.Response::from).toList();
    }

    /** Create a site peer (no user assignment). Client peers must go through
     *  {@code POST /api/v1/users/{userId}/peers} so they have an owner. */
    @POST
    public jakarta.ws.rs.core.Response createSite(@Context ContainerRequestContext ctx,
                                                   @jakarta.validation.Valid PeerDto.CreateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (!"site".equals(body.resolvedType())) {
            throw new jakarta.ws.rs.BadRequestException("POST /api/v1/peers is for site peers only; use /api/v1/users/{userId}/peers for client peers");
        }
        PeerDto.CreateResponse out = peers.createForUser(null, body);
        audit.logCreate(a.principal(), "peer.create", "Peer:" + out.peer().name() + " (" + out.peer().id() + ")",
                Map.of("name", out.peer().name(), "assignedIp", out.peer().assignedIp(),
                        "type", "site", "siteAllowedCidrs", out.peer().siteAllowedCidrs() == null ? "" : out.peer().siteAllowedCidrs(),
                        "publicKey", out.peer().publicKey()));
        rulesets.recomputeFromHook();
        return jakarta.ws.rs.core.Response
                .created(java.net.URI.create("/api/v1/peers/" + out.peer().id()))
                .entity(out).build();
    }

    @GET
    @Path("/next-ip")
    public PeerDto.NextIpResponse nextIp(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return new PeerDto.NextIpResponse(peers.suggestNextIp());
    }

    @GET
    @Path("/next-ip6")
    public PeerDto.NextIpv6Response nextIpv6(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return new PeerDto.NextIpv6Response(peers.suggestNextIpv6());
    }

    /**
     * Live snapshot from {@code wg show <iface> dump} — returns every peer that has
     * exchanged a handshake in the last 3 minutes. Cross-referenced with the DB so
     * the response carries the Islandr peer id, name and type alongside the wg data.
     * Unrecognised public keys (peers not in the DB) are included with id/name null.
     */
    @GET
    @Path("/live")
    public java.util.List<java.util.Map<String, Object>> livePeers(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        java.util.List<WgAdapter.PeerStatus> statuses = wg.showPeers(wgInterface);
        java.time.Instant threshold = java.time.Instant.now().minus(3, java.time.temporal.ChronoUnit.MINUTES);

        // Build a public-key → DB peer map for cross-referencing
        java.util.Map<String, Peer> byKey = Peer.<Peer>listAll().stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.publicKey, p -> p, (a, b) -> a));

        return statuses.stream()
                .filter(s -> s.lastHandshake() != null && s.lastHandshake().isAfter(threshold))
                .map(s -> {
                    Peer p = byKey.get(s.publicKey());
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("publicKey", s.publicKey());
                    m.put("endpoint", s.endpoint());
                    m.put("lastHandshake", s.lastHandshake());
                    m.put("rxBytes", s.rxBytes());
                    m.put("txBytes", s.txBytes());
                    m.put("id",         p != null ? p.id         : null);
                    m.put("name",       p != null ? p.name       : null);
                    m.put("assignedIp", p != null ? p.assignedIp : null);
                    m.put("type",       p != null ? p.type       : null);
                    return m;
                }).toList();
    }

    /**
     * Peers x days connection activity matrix for the dashboard heatmap (#32),
     * aggregated from {@link PeerDailyActivity} (written by {@link ActivityPoller}).
     * {@code days} defaults to 30, clamped to [1, 180] — enough for the useful
     * "recent pattern" view without the client paying for the full retention window.
     * Carries both {@code sampleHits} (connection presence) and {@code rxBytes}/
     * {@code txBytes} (traffic volume) per day — the frontend toggles which one
     * drives the heatmap's color intensity.
     */
    @GET
    @Path("/activity-heatmap")
    public PeerDto.ActivityHeatmapResponse activityHeatmap(@Context ContainerRequestContext ctx,
                                                             @jakarta.ws.rs.QueryParam("days") Integer daysParam) {
        Auth.requireAdmin(ctx);
        int numDays = daysParam == null ? 30 : Math.max(1, Math.min(180, daysParam));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate from = today.minusDays(numDays - 1);

        java.util.List<String> days = new java.util.ArrayList<>();
        for (java.time.LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) days.add(d.toString());

        java.util.List<PeerDailyActivity> rows =
                PeerDailyActivity.find("id.day >= ?1", from.toString()).list();
        java.util.Map<String, java.util.Map<String, PeerDailyActivity>> byPeer = new java.util.HashMap<>();
        for (PeerDailyActivity row : rows) {
            byPeer.computeIfAbsent(row.id.peerId, k -> new java.util.HashMap<>())
                    .put(row.id.day, row);
        }

        java.util.List<PeerDto.ActivityHeatmapRow> peerRows = Peer.<Peer>listAll(Sort.by("name")).stream()
                .map(p -> {
                    java.util.Map<String, PeerDailyActivity> byDay = byPeer.getOrDefault(p.id, java.util.Map.of());
                    java.util.List<Integer> sampleHits = days.stream()
                            .map(d -> byDay.containsKey(d) ? byDay.get(d).sampleHits : 0).toList();
                    java.util.List<Long> rxBytes = days.stream()
                            .map(d -> byDay.containsKey(d) ? byDay.get(d).rxBytes : 0L).toList();
                    java.util.List<Long> txBytes = days.stream()
                            .map(d -> byDay.containsKey(d) ? byDay.get(d).txBytes : 0L).toList();
                    return new PeerDto.ActivityHeatmapRow(p.id, p.name, p.type, sampleHits, rxBytes, txBytes);
                }).toList();

        return new PeerDto.ActivityHeatmapResponse(days, peerRows);
    }

    @GET
    @Path("/wg-import-preview")
    public java.util.List<PeerDto.WgImportCandidate> wgImportPreview(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return peers.wgImportPreview();
    }

    @POST
    @Path("/wg-import")
    public java.util.List<PeerDto.WgImportResult> wgImport(@Context ContainerRequestContext ctx,
                                                            @jakarta.validation.Valid PeerDto.WgImportRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        var results = peers.wgImport(body.peers());
        long imported = results.stream().filter(r -> "imported".equals(r.status())).count();
        if (imported > 0) {
            audit.logCreate(a.principal(), "peer.wg-import", wgInterface,
                    Map.of("imported", imported, "skipped", results.size() - imported));
            rulesets.recomputeFromHook();
        }
        return results;
    }

    @GET
    @Path("/{id}")
    public PeerDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        Peer p = Peer.findById(id);
        if (p == null) throw new NotFoundException("peer not found: " + id);
        return PeerDto.Response.from(p);
    }

    @GET
    @Path("/{id}/conf")
    public PeerDto.CreateResponse reshowConf(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        return peers.reshow(id);
    }

    @PUT
    @Path("/{id}")
    public PeerDto.CreateResponse update(@Context ContainerRequestContext ctx,
                                         @PathParam("id") String id,
                                         @jakarta.validation.Valid PeerDto.UpdateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Peer before = Peer.findById(id);
        Map<String, Object> beforeMap = before == null ? null : peerMutableSnapshot(before);
        PeerDto.CreateResponse out = peers.update(id, body);
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("name", out.peer().name());
        afterMap.put("assignedIp", out.peer().assignedIp());
        afterMap.put("siteAllowedCidrs", out.peer().siteAllowedCidrs() == null ? "" : out.peer().siteAllowedCidrs());
        audit.logUpdate(a.principal(), "peer.update", "Peer:" + out.peer().name() + " (" + id + ")", beforeMap, afterMap);
        rulesets.recomputeFromHook();
        return out;
    }

    /**
     * Read the preshared key for this peer from the live wg interface and store it.
     * Returns 200 with {"imported": true} when a PSK was found and saved,
     * or {"imported": false} when the peer has no PSK in wg.
     * Returns 404 if the peer is not found in the DB, 409 if the peer already
     * has a PSK stored (use the edit form to rotate/remove it explicitly).
     */
    @POST
    @Path("/{id}/psk/sync-from-wg")
    @jakarta.transaction.Transactional
    public Response syncPskFromWg(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Peer peer = Peer.findById(id);
        if (peer == null) throw new NotFoundException("peer not found: " + id);
        if (peer.presharedKey != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "PSK already set — use rotate/remove in the edit form"))
                    .build();
        }
        WgAdapter.PeerStatus status = wg.showPeers(wgInterface).stream()
                .filter(s -> peer.publicKey.equals(s.publicKey()))
                .findFirst().orElse(null);
        if (status == null || status.presharedKey() == null) {
            return Response.ok(Map.of("imported", false)).build();
        }
        peer.presharedKey = status.presharedKey();
        peer.updatedAt = java.time.Instant.now();
        // wg already has the PSK — no need to call setPeer again
        audit.logUpdate(a.principal(), "peer.psk-sync", "Peer:" + peer.name + " (" + id + ")", null, null);
        return Response.ok(Map.of("imported", true)).build();
    }

    @PUT
    @Path("/{id}/enabled")
    public PeerDto.Response setEnabled(@Context ContainerRequestContext ctx,
                                       @PathParam("id") String id,
                                       PeerDto.EnabledRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        PeerDto.Response p = peers.setEnabled(id, body.enabled());
        String action = body.enabled() ? "peer.enable" : "peer.disable";
        audit.logEvent(a.principal(), action, "Peer:" + p.name() + " (" + id + ")",
                Map.of("name", p.name(), "assignedIp", p.assignedIp()));
        rulesets.recomputeFromHook();
        return p;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Peer before = Peer.findById(id);
        Map<String, Object> beforeMap = before == null ? null : peerSnapshot(before);
        peers.delete(id);
        String peerName = before != null ? before.name : id;
        audit.logDelete(a.principal(), "peer.delete", "Peer:" + peerName + " (" + id + ")", beforeMap);
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }

    /** Full snapshot for create/delete audit — captures every relevant field. */
    static Map<String, Object> peerSnapshot(Peer p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("userId", p.userId);
        m.put("assignedIp", p.assignedIp);
        m.put("type", p.type);
        m.put("siteAllowedCidrs", p.siteAllowedCidrs == null ? "" : p.siteAllowedCidrs);
        m.put("enabled", p.enabled);
        m.put("publicKey", p.publicKey);
        return m;
    }

    /** Snapshot of only the mutable fields touched by PUT /{id}.
     *  Keeping the same key-set in before/after lets AuditDiff show a clean diff. */
    static Map<String, Object> peerMutableSnapshot(Peer p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("assignedIp", p.assignedIp);
        m.put("siteAllowedCidrs", p.siteAllowedCidrs == null ? "" : p.siteAllowedCidrs);
        return m;
    }
}
