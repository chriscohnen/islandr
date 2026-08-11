package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.dashboard.DashboardDto;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Returns all resources the authenticated user has access to, with only
 * the ports their role grants expose. Used by the self-service portal to
 * show "what can I reach?". Admins can impersonate any user via ?userId=.
 */
@ApplicationScoped
@Path("/api/v1/acl/my-resources")
@Produces(MediaType.APPLICATION_JSON)
public class MyAccessResource {

    @Inject SettingsService settings;
    @Inject AclResolutionService resolution;

    @GET
    public ResourceDto.MyAccessResponse myResources(
            @Context ContainerRequestContext ctx,
            @QueryParam("userId") String userIdParam) {
        // The portal-level flags travel with the user's own payload because end
        // users cannot read the admin-only /settings endpoint (design 2026-06-28).
        return new ResourceDto.MyAccessResponse(
                settings.get().ironRdpEnabled,
                resolveResources(ctx, userIdParam));
    }

    /** Gateway counts as reachable if it handshook this recently — same window
     *  as the admin dashboard's topology (DashboardResource.TOPOLOGY_LIVE_WINDOW). */
    private static final Duration TOPOLOGY_LIVE_WINDOW = Duration.ofMinutes(5);

    /**
     * Portal-scoped topology (#43): reuses the exact same ACL resolution as
     * {@link #myResources} — a network/site appears here if and only if the
     * user has at least one resource grant in it (concrete or type-grant);
     * a network with zero grants is entirely absent, not just grayed out
     * (2026-08-04 scope clarification on the issue). Reuses the admin
     * dashboard's {@code DashboardDto.Topology} shape so the frontend can
     * feed the very same {@code TopologyDiagram.js}/{@code TopologyWorldMap.js}
     * components — but with the Admin-only technical fields (CIDR, gateway's
     * own tunnel IP, raw handshake timestamp) nulled out. Those components
     * already null-guard on missing data class fields; the `portal` prop
     * added there swaps the remaining Admin-worded fallback text.
     */
    @GET
    @Path("/topology")
    public DashboardDto.Topology topology(
            @Context ContainerRequestContext ctx,
            @QueryParam("userId") String userIdParam) {
        List<ResourceDto.MyAccessResource> granted = resolveResources(ctx, userIdParam);

        Map<String, List<ResourceDto.MyAccessResource>> bySite = granted.stream()
                .collect(Collectors.groupingBy(ResourceDto.MyAccessResource::siteId));

        List<Site> sites = bySite.isEmpty()
                ? List.of()
                : Site.<Site>list("id in ?1 order by name", bySite.keySet());

        Set<String> gatewayIds = sites.stream()
                .map(s -> s.gatewayPeerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Instant gatewayThreshold = Instant.now().minus(TOPOLOGY_LIVE_WINDOW);
        Map<String, Peer> gatewayPeerById = new HashMap<>();
        if (!gatewayIds.isEmpty()) {
            Peer.<Peer>list("id in ?1", gatewayIds).forEach(p -> gatewayPeerById.put(p.id, p));
        }

        List<DashboardDto.TopologySite> topoSites = sites.stream().map(site -> {
            Peer gw = gatewayPeerById.get(site.gatewayPeerId);
            Boolean gwOnline = gw == null ? null
                    : (gw.lastSeenAt != null && gw.lastSeenAt.isAfter(gatewayThreshold));
            return new DashboardDto.TopologySite(
                    site.id, site.name,
                    null, // cidr — never shown to end users (CLAUDE.md Portal register)
                    bySite.get(site.id).size(),
                    site.gatewayPeerId,
                    gw == null ? null : gw.name,
                    gwOnline,
                    null, // gatewayIp — Admin-technical detail, not "is it reachable"
                    null, // gatewayLastSeenAt — ditto; portal shows connected/disconnected only
                    gw == null ? null : gw.lat,
                    gw == null ? null : gw.lng);
        }).toList();

        List<DashboardDto.TopologyResource> topoResources = granted.stream()
                .map(r -> new DashboardDto.TopologyResource(
                        r.id(), r.siteId(), r.name(), r.ip(), r.type(),
                        r.grantedPorts().stream().map(MyAccessResource::portLabel).toList(),
                        r.grantedPorts().size()))
                .toList();

        return new DashboardDto.Topology(
                topoSites, topoResources, List.of(), 0,
                null, // hubEndpoint — Admin-technical, and the portal hub node is decorative only
                settings.get().hubLocationLabel,
                settings.get().hubLat,
                settings.get().hubLon);
    }

    private static String portLabel(ResourceDto.PortResponse p) {
        String transport = p.transport() == null ? "" : p.transport().toUpperCase();
        String protocol = p.protocol();
        return protocol == null || protocol.isBlank() || "CUSTOM".equalsIgnoreCase(protocol)
                ? transport + " " + p.port()
                : protocol + " " + p.port();
    }

    private List<ResourceDto.MyAccessResource> resolveResources(
            ContainerRequestContext ctx,
            String userIdParam) {

        AuthContext a = Auth.require(ctx);

        // Admins may request the view for any user to preview what they see.
        // Non-admins can only see their own resources.
        String userId;
        if (userIdParam != null && !userIdParam.isBlank()) {
            Auth.requireAdmin(ctx);
            userId = userIdParam;
        } else {
            if (a.userId() == null) return List.of();  // local ENV admin has no userId
            userId = a.userId();
        }

        return resolution.resolveMyAccess(userId);
    }
}
