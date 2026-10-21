package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
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
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-service peer endpoints. The caller must be an authenticated org user
 * (not the ENV-bootstrap local admin — that one has no users row, so "mine"
 * has no meaning). Every operation is scoped to {@code session.userId} and
 * client-type peers only; site peers stay an admin concern.
 */
@Path("/api/v1/peers/mine")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MyPeerResource {

    @Inject PeerService peers;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;
    @Inject de.chriscohnen.islandr.settings.SettingsService settings;
    @Inject de.chriscohnen.islandr.acl.AclResolutionService acl;
    @Inject PeerSelfShareService shares;
    @Inject de.chriscohnen.islandr.dns.DnsQueryHandler dns;

    @RegisterForReflection
    public record CreateMineRequest(
            @NotBlank String name,

            // Optional. If empty the server generates a fresh keypair and
            // returns the private half once in the response. If provided, the
            // user keeps the private half on their device — the server never
            // sees it (mirrors the "publicKey only" admin import mode).
            @Pattern(regexp = "^$|^[A-Za-z0-9+/]{43}=$",
                    message = "must be a 44-char Base64 WireGuard key")
            String publicKey,

            // Optional device category picked in the portal's "what kind of
            // device is this?" step. Same enum as the admin peer modal.
            @Pattern(regexp = "^$|^(laptop|desktop|mobile|tablet|server|other)$",
                    message = "deviceType must be one of: laptop, desktop, mobile, tablet, server, other")
            String deviceType,

            // Which hub this device will dial (ADR-I-0003). Empty = the
            // central hub. Only an edge the user can actually reach something
            // at is accepted — see edgeOptions() for why that is the gate.
            String edgeHubId
    ) {}

    /** One hub the signed-in user may put a device on. */
    public record EdgeOption(String id, String name, String locationLabel) {}

    @RegisterForReflection
    public record UpdateMineRequest(
            @NotBlank String name,

            // Same enum as creation; "" clears it back to none.
            @Pattern(regexp = "^$|^(laptop|desktop|mobile|tablet|server|other)$",
                    message = "deviceType must be one of: laptop, desktop, mobile, tablet, server, other")
            String deviceType,

            // peer-roadwarrior-badge: "I'm currently traveling with this
            // device" — the owner's own call, toggled as often as their
            // situation changes.
            boolean isRoadwarrior
    ) {}

    @RegisterForReflection
    public record RotateKeyRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9+/]{43}=$",
                    message = "must be a 44-char Base64 WireGuard key")
            String publicKey
    ) {}

    @GET
    public List<PeerDto.Response> listMine(@Context ContainerRequestContext ctx) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        // A peer with a pending deletion request is gone as far as its owner
        // is concerned (they asked for exactly that) — it still exists for
        // requestDeletionMine, ownedOr404, and the admin list, which is what
        // lets an admin actually finish the removal.
        List<Peer> mine = Peer.<Peer>list("userId = ?1 and deletionRequestedAt is null",
                Sort.by("createdAt").descending(), userId);
        // myaccess-peer-dns-name-display: one peerDnsFqdns() build for the
        // whole list, not one per peer (see that method's own doc comment).
        Map<String, String> fqdns = dns.peerDnsFqdns(mine);
        // myaccess-share-status-indicator: same bulk-not-per-row shape for
        // live share counts.
        List<String> peerIds = mine.stream().map(p -> p.id).toList();
        Map<String, Long> shareCounts = PeerSelfShare.liveCountsByOwnerPeerIds(peerIds, java.time.Instant.now());
        // portal-shows-device-hub: the name of each peer's own edge, resolved
        // here rather than in the browser — /edge-options only lists edges the
        // user can still reach, and an admin may have assigned a peer to one
        // they cannot. Empty while the feature is off, so a portal that was
        // once an edge install says nothing about a concept the operator has
        // switched off.
        Map<String, String> hubNames = edgeHubNamesFor(mine);
        return mine.stream()
                .map(p -> PeerDto.Response.from(p, fqdns.get(p.id), shareCounts.getOrDefault(p.id, 0L),
                        // Not hubNames.get(p.edgeHubId): the map is a Map.of()
                        // when there is nothing to resolve, and that throws on
                        // a null key — which is every peer at the central hub.
                        p.edgeHubId == null ? null : hubNames.get(p.edgeHubId)))
                .toList();
    }

    /**
     * Own-activity heatmap (#43): same {@code peer_daily_activity} source and
     * response shape as the admin's {@code GET /api/v1/peers/activity-heatmap},
     * just constrained to the caller's own peers before building rows — no
     * other user's device activity is ever visible here.
     */
    @GET
    @Path("/activity-heatmap")
    public PeerDto.ActivityHeatmapResponse activityHeatmap(
            @Context ContainerRequestContext ctx,
            @jakarta.ws.rs.QueryParam("days") Integer daysParam,
            @jakarta.ws.rs.QueryParam("userId") String userIdParam) {
        // Same admin-impersonation pattern as MyAccessResource.myResources —
        // lets an admin's "view as" preview show that user's own activity
        // instead of always the admin session's own (issue #43 follow-up:
        // this endpoint originally only supported the caller's own peers,
        // which broke "view as" for this one tab).
        String userId;
        if (userIdParam != null && !userIdParam.isBlank()) {
            Auth.requireAdmin(ctx);
            userId = userIdParam;
        } else {
            AuthContext a = Auth.require(ctx);
            userId = requireOrgUserId(a);
        }

        int numDays = daysParam == null ? 30 : Math.max(1, Math.min(180, daysParam));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(numDays - 1);

        List<String> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) days.add(d.toString());

        List<Peer> myPeers = Peer.<Peer>list("userId = ?1", Sort.by("name"), userId);
        List<String> myPeerIds = myPeers.stream().map(p -> p.id).toList();
        List<PeerDailyActivity> rows = myPeerIds.isEmpty()
                ? List.of()
                : PeerDailyActivity.find(
                        "id.peerId in ?1 and id.day >= ?2", myPeerIds, from.toString()).list();
        Map<String, Map<String, PeerDailyActivity>> byPeer = new HashMap<>();
        for (PeerDailyActivity row : rows) {
            byPeer.computeIfAbsent(row.id.peerId, k -> new HashMap<>()).put(row.id.day, row);
        }

        // Same value on every row here — every peer in this response belongs to
        // the one userId this call resolved above — but kept in the response
        // rather than special-cased away, since MyPeerResource explicitly
        // promises the admin endpoint's own shape (see this method's Javadoc).
        de.chriscohnen.islandr.user.User owner = de.chriscohnen.islandr.user.User.findById(userId);
        String userName = owner != null ? owner.name : null;

        List<PeerDto.ActivityHeatmapRow> peerRows = myPeers.stream()
                .map(p -> {
                    Map<String, PeerDailyActivity> byDay = byPeer.getOrDefault(p.id, Map.of());
                    List<Integer> sampleHits = days.stream()
                            .map(d -> byDay.containsKey(d) ? byDay.get(d).sampleHits : 0).toList();
                    List<Long> rxBytes = days.stream()
                            .map(d -> byDay.containsKey(d) ? byDay.get(d).rxBytes : 0L).toList();
                    List<Long> txBytes = days.stream()
                            .map(d -> byDay.containsKey(d) ? byDay.get(d).txBytes : 0L).toList();
                    return new PeerDto.ActivityHeatmapRow(p.id, p.name, p.type, p.deviceType, sampleHits, rxBytes, txBytes, p.createdAt,
                            p.userId, userName);
                }).toList();

        return new PeerDto.ActivityHeatmapResponse(days, peerRows);
    }

    /**
     * Own-traffic ranking (peer-traffic-ranking, self-service half): same
     * window/response shape as the admin's {@code GET /api/v1/peers/traffic-
     * ranking}, restricted to the caller's own peers — so a user who sends a
     * lot of traffic can see that for themselves, in the same bytes an admin
     * would see across everyone, not a separately-computed approximation.
     * Same admin-impersonation pattern as {@link #activityHeatmap} for "view
     * as".
     */
    @GET
    @Path("/traffic-ranking")
    public PeerDto.TrafficRankingResponse trafficRankingMine(
            @Context ContainerRequestContext ctx,
            @jakarta.ws.rs.QueryParam("window") String windowParam,
            @jakarta.ws.rs.QueryParam("userId") String userIdParam) {
        String userId;
        if (userIdParam != null && !userIdParam.isBlank()) {
            Auth.requireAdmin(ctx);
            userId = userIdParam;
        } else {
            AuthContext a = Auth.require(ctx);
            userId = requireOrgUserId(a);
        }
        java.util.Set<String> myPeerIds = Peer.<Peer>list("userId", userId)
                .stream().map(p -> p.id).collect(java.util.stream.Collectors.toSet());
        return peers.trafficRanking(windowParam, myPeerIds);
    }

    /**
     * The edge hubs this user could put a device on — the central hub is not
     * in the list, it is the empty default.
     *
     * <p>Gated by reach, not by existence: a device at an edge where its owner
     * may not touch anything would be a tunnel to nowhere. That rule predates
     * this endpoint (the decision of 2026-10-02) and is already computed by
     * {@code reachableEdgeHubIdsForUser}; here it simply decides what the portal
     * offers.
     *
     * <p>Empty whenever edge hubs are switched off for the installation, so
     * the portal needs no second call to find that out.
     */
    @GET
    @Path("/edge-options")
    public java.util.List<EdgeOption> edgeOptions(@Context ContainerRequestContext ctx,
                                                  @jakarta.ws.rs.QueryParam("userId") String userIdParam) {
        // Same "view as" shape as the activity heatmap below. Barely
        // reachable today: the "add device" button is hidden while an admin
        // previews someone else's portal — except in the empty state, where
        // it is not (MyAccessView, two buttons, only one guarded). Kept
        // because it costs nothing and the alternative is an endpoint that
        // silently answers for the wrong person if that second button is ever
        // used; the guard belongs on the button, not here.
        String userId;
        if (userIdParam != null && !userIdParam.isBlank()) {
            Auth.requireAdmin(ctx);
            userId = userIdParam;
        } else {
            userId = requireOrgUserId(Auth.require(ctx));
        }
        return edgeOptionsFor(userId);
    }

    /** id → label of every edge hub the given peers dial in at, or empty while
     *  edge hubs are switched off. One query for the whole list, not one per
     *  row — same shape as the DNS names and share counts above. */
    private Map<String, String> edgeHubNamesFor(List<Peer> peers) {
        if (!settings.get().edgeHubsEnabled) return Map.of();
        java.util.Set<String> ids = peers.stream()
                .map(p -> p.edgeHubId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return de.chriscohnen.islandr.acl.EdgeHub.<de.chriscohnen.islandr.acl.EdgeHub>list("id in ?1", ids)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.id,
                        e -> e.locationLabel != null && !e.locationLabel.isBlank() ? e.locationLabel : e.name));
    }

    private java.util.List<EdgeOption> edgeOptionsFor(String userId) {
        if (!settings.get().edgeHubsEnabled) return java.util.List.of();
        java.util.Set<String> reachable = acl.reachableEdgeHubIdsForUser(userId);
        if (reachable.isEmpty()) return java.util.List.of();
        return de.chriscohnen.islandr.acl.EdgeHub.<de.chriscohnen.islandr.acl.EdgeHub>listAll().stream()
                .filter(e -> e.enabled && e.canServePeers() && reachable.contains(e.id))
                .map(e -> new EdgeOption(e.id, e.name, e.locationLabel))
                .toList();
    }

    /**
     * A self-service user may only put a device on an edge the list above
     * offers. Checked server-side as well as hidden in the portal, because
     * the portal's list is a convenience and this is the rule.
     */
    private String validateSelfServiceEdge(String userId, String edgeHubId) {
        if (edgeHubId == null || edgeHubId.isBlank()) return null;
        boolean offered = edgeOptionsFor(userId).stream().anyMatch(o -> o.id().equals(edgeHubId));
        if (!offered) {
            throw new jakarta.ws.rs.BadRequestException(
                    "that edge hub is not available to you — you can only add a device at a location "
                    + "where you have access to something.");
        }
        return edgeHubId;
    }

    @POST
    public Response createMine(@Context ContainerRequestContext ctx,
                               @Valid CreateMineRequest body) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        if (!settings.get().selfServicePeerCreation) {
            throw new ForbiddenException("self-service peer creation is disabled by the administrator");
        }
        // The bypass issue #53 is actually about: a user whose access window
        // has closed could create a brand-new, unlimited peer here and carry
        // on. SessionFilter already rejects them, but this states the rule at
        // the place it protects rather than relying on a distant filter.
        de.chriscohnen.islandr.user.User me = de.chriscohnen.islandr.user.User.findById(userId);
        if (me == null || !me.accessAllowedAt(java.time.Instant.now())) {
            throw new ForbiddenException("your access has expired — ask an administrator to extend it");
        }
        // IP is server-chosen for self-service; user never picks a CIDR slot.
        // Type is forced to "client" — site peers are an admin operation.
        Integer autoMtu = PeerService.needsCompatMtu(body.deviceType(), false)
                ? PeerService.MOBILE_DEFAULT_MTU : null;
        PeerDto.CreateRequest req = new PeerDto.CreateRequest(
                body.name(),
                peers.suggestNextIp(),
                null,   // assignedIpv6 — server does not assign IPv6 for self-service
                body.publicKey(),
                null,
                "client",
                null,
                body.deviceType(),
                autoMtu,
                false,  // generatePresharedKey — PSK is an admin-only option
                null, null, null, // lat/lng/locationLabel — site-only, n/a for self-service client peers
                validateSelfServiceEdge(userId, body.edgeHubId()));
        PeerDto.CreateResponse out = peers.createForUser(userId, req);
        // nftables recompute happens inside createForUser (saga step 2).
        audit.logCreate(a.principal(), "peer.create", "Peer:" + out.peer().name() + " (" + out.peer().id() + ")",
                Map.of(
                        "name", out.peer().name(),
                        "userId", userId,
                        "assignedIp", out.peer().assignedIp(),
                        "type", "client",
                        "publicKey", out.peer().publicKey(),
                        "selfService", true));
        return Response.status(Response.Status.CREATED).entity(out).build();
    }

    @GET
    @Path("/{id}/conf")
    public PeerDto.CreateResponse reshowMine(@Context ContainerRequestContext ctx,
                                             @PathParam("id") String id,
                                             @jakarta.ws.rs.QueryParam("userId") String userIdParam) {
        // Same admin-impersonation pattern as activityHeatmap (#43 follow-up):
        // "view as" must be able to re-show/download the viewed user's own
        // config, not the admin's own, or QR/.conf 404s on every peer that
        // isn't the admin's own.
        String userId;
        if (userIdParam != null && !userIdParam.isBlank()) {
            Auth.requireAdmin(ctx);
            userId = userIdParam;
        } else {
            AuthContext a = Auth.require(ctx);
            userId = requireOrgUserId(a);
        }
        ownedOr404(id, userId);
        // Without a stored private key the .conf comes back without its
        // PrivateKey line and without a QR code. Still worth having: address,
        // DNS, endpoint and allowed IPs are what a user re-typing a config
        // by hand gets wrong, and the key they can paste from the device.
        return peers.reshow(id);
    }

    /** Rename a device and/or change its category (issue: peer-self-edit).
     *  IP, CIDR and ownership stay an admin-only concern reachable through
     *  {@code PUT /api/v1/peers/{id}} — this endpoint cannot touch them. */
    @PUT
    @Path("/{id}")
    public PeerDto.Response updateMine(@Context ContainerRequestContext ctx,
                                       @PathParam("id") String id,
                                       @Valid UpdateMineRequest body) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        Peer existing = ownedOr404(id, userId);
        String oldName = existing.name;
        String oldDeviceType = existing.deviceType;
        boolean oldRoadwarrior = existing.isRoadwarrior;
        PeerDto.Response out = peers.updateSelfDetails(id, body.name(), body.deviceType(), body.isRoadwarrior());
        audit.logUpdate(a.principal(), "peer.update", "Peer:" + out.name() + " (" + id + ")",
                Map.of("name", oldName, "deviceType", oldDeviceType == null ? "" : oldDeviceType,
                        "isRoadwarrior", oldRoadwarrior),
                Map.of("name", out.name(), "deviceType", out.deviceType() == null ? "" : out.deviceType(),
                        "isRoadwarrior", out.isRoadwarrior()));
        return out;
    }

    @PUT
    @Path("/{id}/public-key")
    public PeerDto.Response rotateMine(@Context ContainerRequestContext ctx,
                                       @PathParam("id") String id,
                                       @Valid RotateKeyRequest body) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        Peer existing = ownedOr404(id, userId);
        String oldKey = existing.publicKey;
        PeerDto.Response out = peers.rotatePublicKey(id, body.publicKey());
        // Both halves redacted-free here — public keys are non-secret. Storing
        // the previous key in 'before' so an investigator can correlate which
        // client rotated when, against `wg show` history.
        audit.logUpdate(a.principal(), "peer.key_rotate", "Peer:" + id,
                Map.of("publicKey", oldKey),
                Map.of("publicKey", out.publicKey()));
        // Public key doesn't appear in the nftables rules (which are IP:port
        // tuples), but the kernel-side wg map changed and a recompute keeps
        // any future "rule based on peer" generation consistent. Cheap.
        rulesets.recomputeFromHook();
        return out;
    }

    /**
     * Self-service "soft delete" (issue: users-self-delete-peer). The device
     * disappears from the caller's own list and stops working immediately,
     * but the row itself stays — only an admin's real {@code DELETE
     * /api/v1/peers/{id}} removes it. 202, not 204: the caller's request is
     * accepted, but nothing has actually been deleted yet.
     */
    @DELETE
    @Path("/{id}")
    @ResponseStatus(202)
    public PeerDto.Response requestDeletionMine(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        ownedOr404(id, userId);
        PeerDto.Response out = peers.requestDeletion(id);
        audit.logEvent(a.principal(), "peer.deletion_requested", "Peer:" + id,
                Map.of("selfService", true));
        rulesets.recomputeFromHook();
        return out;
    }

    // ---- peer-self-share: sharing one of my own ports with a colleague ----

    @POST
    @Path("/{id}/shares")
    @ResponseStatus(201)
    public PeerSelfShareDto.Response createShare(@Context ContainerRequestContext ctx,
                                                 @PathParam("id") String id,
                                                 @Valid PeerSelfShareDto.CreateRequest body) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        PeerSelfShare share = shares.create(id, userId, body.targetEmail(), body.port(), body.durationMinutes());
        audit.logEvent(a.principal(), "peer.share_created", "Peer:" + id,
                Map.of("targetEmail", body.targetEmail(), "port", body.port(),
                        "durationMinutes", body.durationMinutes()));
        rulesets.recomputeFromHook();
        return PeerSelfShareDto.Response.from(share, java.time.Instant.now());
    }

    @GET
    @Path("/{id}/shares")
    public List<PeerSelfShareDto.Response> listShares(@Context ContainerRequestContext ctx,
                                                       @PathParam("id") String id) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        java.time.Instant now = java.time.Instant.now();
        List<PeerSelfShareDto.Response> out = new ArrayList<>();
        for (PeerSelfShare s : shares.listOwn(id, userId)) out.add(PeerSelfShareDto.Response.from(s, now));
        return out;
    }

    @DELETE
    @Path("/shares/{shareId}")
    public Response revokeShare(@Context ContainerRequestContext ctx, @PathParam("shareId") String shareId) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        PeerSelfShare share = shares.revoke(shareId, userId);
        audit.logEvent(a.principal(), "peer.share_revoked", "Peer:" + share.ownerPeerId,
                Map.of("shareId", shareId));
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }

    private static Peer ownedOr404(String peerId, String userId) {
        Peer p = Peer.findById(peerId);
        // Hide the existence of someone else's peer behind the same 404 we'd
        // send for an unknown id, so a probing user can't enumerate the table.
        if (p == null || !userId.equals(p.userId)) {
            throw new NotFoundException("peer not found: " + peerId);
        }
        return p;
    }

    private static String requireOrgUserId(AuthContext a) {
        if (a.userId() == null) {
            // The ENV-bootstrap admin has no users row — "mine" cannot resolve.
            // It lives under /api/v1/peers (admin) anyway.
            throw new ForbiddenException("local admin has no self-service peers — use /api/v1/peers");
        }
        return a.userId();
    }
}
