package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.dashboard.DashboardDto;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    @PersistenceContext EntityManager em;
    @Inject SettingsService settings;

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

        // 1. Roles this user belongs to — explicit memberships plus every auto_all
        //    role (Everyone), which includes all users implicitly (ADR-0013).
        @SuppressWarnings("unchecked")
        List<String> roleIds = em.createNativeQuery(
                        "SELECT role_id FROM user_roles WHERE user_id = ?1 "
                                + "UNION SELECT id FROM roles WHERE auto_all = 1")
                .setParameter(1, userId)
                .getResultList();
        if (roleIds.isEmpty()) return List.of();

        // 2. Grants for those roles.
        @SuppressWarnings("unchecked")
        List<Object[]> grantRows = em.createNativeQuery(
                        "SELECT id, resource_id, all_ports FROM role_resource_grants WHERE role_id IN ?1")
                .setParameter(1, roleIds)
                .getResultList();

        // 2b. Type grants ("all printers in Homeoffice") — every resource whose
        // site+type matches one of these roles' type-grants, always all-ports.
        // Union with the concrete grants above, not a replacement — see
        // RoleResourceTypeGrant's own doc comment for why this is additive-only.
        @SuppressWarnings("unchecked")
        List<String> typeGrantResourceIds = em.createNativeQuery(
                        "SELECT r.id FROM resources r " +
                        "JOIN role_resource_type_grants g ON g.site_id = r.site_id AND g.resource_type = r.type " +
                        "WHERE g.role_id IN ?1")
                .setParameter(1, roleIds)
                .getResultList();

        if (grantRows.isEmpty() && typeGrantResourceIds.isEmpty()) return List.of();

        // 3. Limited-port sets for grants that don't cover all ports.
        Set<String> grantIds = new HashSet<>();
        for (Object[] row : grantRows) if (!(Boolean) row[2]) grantIds.add((String) row[0]);

        Map<String, Set<String>> portsByGrant = new HashMap<>();
        if (!grantIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createNativeQuery(
                            "SELECT grant_id, port_id FROM role_resource_grant_ports WHERE grant_id IN ?1")
                    .setParameter(1, grantIds)
                    .getResultList();
            for (Object[] r : portRows) {
                portsByGrant.computeIfAbsent((String) r[0], k -> new HashSet<>()).add((String) r[1]);
            }
        }

        // 4. Merge grants per resource — union of all port sets, all_ports wins.
        // key = resourceId → { allPorts: bool, portIds: Set }
        record EffectiveGrant(boolean allPorts, Set<String> portIds) {}
        Map<String, EffectiveGrant> effective = new LinkedHashMap<>();
        for (Object[] row : grantRows) {
            String grantId    = (String)  row[0];
            String resourceId = (String)  row[1];
            boolean allPorts  = (Boolean) row[2];
            EffectiveGrant existing = effective.get(resourceId);
            if (existing != null && existing.allPorts()) continue;  // already widest
            if (allPorts) {
                effective.put(resourceId, new EffectiveGrant(true, Set.of()));
            } else {
                Set<String> ids = portsByGrant.getOrDefault(grantId, Set.of());
                if (existing == null) {
                    effective.put(resourceId, new EffectiveGrant(false, new HashSet<>(ids)));
                } else {
                    existing.portIds().addAll(ids);
                }
            }
        }
        // Type grants always widen to all-ports — unconditional overwrite is
        // safe here (idempotent whether or not a narrower grant already set
        // this resourceId; all-ports is always the correct, widest result).
        for (String resourceId : typeGrantResourceIds) {
            effective.put(resourceId, new EffectiveGrant(true, Set.of()));
        }

        // 5. Load resources + their ports + site names.
        List<String> resourceIds = new ArrayList<>(effective.keySet());
        @SuppressWarnings("unchecked")
        List<Object[]> resRows = em.createNativeQuery(
                        "SELECT r.id, r.site_id, r.name, r.ip, r.description, r.type, s.name " +
                        "FROM resources r JOIN sites s ON s.id = r.site_id " +
                        "WHERE r.id IN ?1 ORDER BY s.name, r.name")
                .setParameter(1, resourceIds)
                .getResultList();

        // All ports for the relevant resources.
        @SuppressWarnings("unchecked")
        List<Object[]> portRows = em.createNativeQuery(
                        "SELECT id, resource_id, port, port_end, transport, protocol, label, path_prefix, " +
                        "rdp_clipboard, rdp_file_transfer, rdp_access_mode " +
                        "FROM resource_ports WHERE resource_id IN ?1 ORDER BY port")
                .setParameter(1, resourceIds)
                .getResultList();
        Map<String, List<ResourceDto.PortResponse>> portsByResource = new HashMap<>();
        for (Object[] p : portRows) {
            String rid = (String) p[1];
            Integer portEnd = p[3] == null ? null : ((Number) p[3]).intValue();
            boolean rdpClipboard = p[8] == null || ((Number) p[8]).intValue() != 0;
            boolean rdpFileTransfer = p[9] != null && ((Number) p[9]).intValue() != 0;
            String rdpAccessMode = p[10] != null ? (String) p[10] : "native";
            portsByResource.computeIfAbsent(rid, k -> new ArrayList<>()).add(
                    new ResourceDto.PortResponse(
                            (String) p[0],
                            ((Number) p[2]).intValue(),
                            portEnd,
                            (String) p[4],
                            (String) p[5],
                            (String) p[6],
                            (String) p[7],
                            rdpClipboard,
                            rdpFileTransfer,
                            rdpAccessMode,
                            null));
        }

        // 6. Build response — filter ports to what the grant actually allows.
        List<ResourceDto.MyAccessResource> out = new ArrayList<>(resRows.size());
        for (Object[] r : resRows) {
            String rid = (String) r[0];
            EffectiveGrant grant = effective.get(rid);
            List<ResourceDto.PortResponse> allPorts = portsByResource.getOrDefault(rid, List.of());
            List<ResourceDto.PortResponse> granted = grant.allPorts()
                    ? allPorts
                    : allPorts.stream().filter(p -> grant.portIds().contains(p.id())).toList();
            out.add(new ResourceDto.MyAccessResource(
                    rid,
                    (String) r[1],
                    (String) r[6],
                    (String) r[2],
                    (String) r[3],
                    (String) r[4],
                    (String) r[5],
                    granted));
        }
        return out;
    }
}
