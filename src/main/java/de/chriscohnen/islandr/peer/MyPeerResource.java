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

    // Device categories the portal offers at creation time. "mobile"/"tablet"
    // ("on the go") default to MTU 1280 — the compatibility floor that always
    // works on LTE/5G roaming or a restrictive hotel Wi-Fi — so end users
    // never have to know what MTU even means (#31 phase 2). Stationary
    // categories get no override; the hub/global default applies.
    private static final java.util.Set<String> MOBILE_DEVICE_TYPES = java.util.Set.of("mobile", "tablet");
    private static final int MOBILE_DEFAULT_MTU = 1280;

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
            String deviceType
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
        return Peer.<Peer>list("userId = ?1", Sort.by("createdAt").descending(), userId)
                .stream().map(PeerDto.Response::from).toList();
    }

    @POST
    public Response createMine(@Context ContainerRequestContext ctx,
                               @Valid CreateMineRequest body) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        if (!settings.get().selfServicePeerCreation) {
            throw new ForbiddenException("self-service peer creation is disabled by the administrator");
        }
        // IP is server-chosen for self-service; user never picks a CIDR slot.
        // Type is forced to "client" — site peers are an admin operation.
        Integer autoMtu = body.deviceType() != null && MOBILE_DEVICE_TYPES.contains(body.deviceType())
                ? MOBILE_DEFAULT_MTU : null;
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
                null, null, null); // lat/lng/locationLabel — site-only, n/a for self-service client peers
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
                                             @PathParam("id") String id) {
        AuthContext a = Auth.require(ctx);
        String userId = requireOrgUserId(a);
        Peer p = ownedOr404(id, userId);
        if (p.privateKeyPem == null) {
            // No stored private key — the conf would be useless to the end user
            // (they can't import it). Send 404 instead of a half-conf they
            // can't actually use. The frontend hides the "show again" button
            // in this case anyway.
            throw new NotFoundException("no stored .conf for this peer — re-add the device");
        }
        return peers.reshow(id);
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
