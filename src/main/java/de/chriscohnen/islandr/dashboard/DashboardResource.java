package de.chriscohnen.islandr.dashboard;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.firewall.FirewallState;
import de.chriscohnen.islandr.identity.OidcProvider;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only KPI roll-up for the admin dashboard. One round-trip instead of
 * six against individual list endpoints. All counts are point-in-time —
 * the UI re-fetches on the manual "Aktualisieren" button.
 */
@Path("/api/v1/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DashboardResource {

    /** How many recent audit + peer rows the strip shows. Small by design. */
    static final int STRIP_SIZE = 8;

    /**
     * Cap for resources on the topology outer ring. Above this the labels
     * collide. Frontend renders the rest as "+N weitere".
     */
    static final int TOPOLOGY_RESOURCE_CAP = 24;

    /** Live-peer window: a peer counts as "live" if it handshook this recently. */
    static final Duration TOPOLOGY_LIVE_WINDOW = Duration.ofMinutes(5);

    @PersistenceContext EntityManager em;

    @GET
    public DashboardDto.Response get(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);

        long peerTotal = Peer.count();
        long peerEnabled = Peer.count("enabled", true);
        long lastSeen24h = Peer.count(
                "lastSeenAt is not null and lastSeenAt >= ?1",
                Instant.now().minus(Duration.ofHours(24)));

        long userTotal = User.count();
        long userAdmins = User.count("isAdmin", true);

        long roleTotal = Role.count();
        long rolesWithGrants = ((Number) em.createQuery(
                        "select count(distinct g.roleId) from RoleResourceGrant g")
                .getSingleResult()).longValue();

        long siteTotal = Site.count();
        long resTotal = Resource.count();
        long portTotal = ResourcePort.count();

        Settings s = Settings.findById(Settings.SINGLETON_ID);
        OidcProvider activeOidc = OidcProvider.find("enabled", true).firstResult();
        DashboardDto.SetupStatus setup = new DashboardDto.SetupStatus(
                s != null && !s.wgServerPublicKey.startsWith("PLACEHOLDER"),
                activeOidc == null ? null : activeOidc.providerKey,
                s == null ? "never" : s.privateKeyRetention,
                s != null && s.gravatarEnabled);

        List<DashboardDto.AuditEntry> audit = AuditLog.<AuditLog>find(
                "order by createdAt desc, id desc").page(0, STRIP_SIZE).list().stream()
                .map(a -> new DashboardDto.AuditEntry(a.id, a.actor, a.action, a.target, a.createdAt))
                .toList();

        // Recent peers: prefer lastSeenAt (when activity-poller is live);
        // fall back to createdAt for the bootstrap case where nothing has
        // checked in yet. Both branches deliver something useful for the UI.
        List<Peer> peerRows = Peer.<Peer>find(
                        "order by case when lastSeenAt is null then 0 else 1 end desc, "
                                + "lastSeenAt desc, createdAt desc")
                .page(0, STRIP_SIZE).list();
        // Name resolution: one IN query for the involved users, then a map lookup.
        Map<String, String> userNames = new HashMap<>();
        if (!peerRows.isEmpty()) {
            List<String> userIds = peerRows.stream().map(p -> p.userId).distinct().toList();
            for (User u : User.<User>list("id in ?1", userIds)) {
                userNames.put(u.id, u.name);
            }
        }
        List<DashboardDto.PeerEntry> peers = peerRows.stream()
                .map(p -> new DashboardDto.PeerEntry(
                        p.id, p.name, p.userId,
                        userNames.getOrDefault(p.userId, "(gelöscht)"),
                        p.assignedIp, p.enabled, p.lastSeenAt))
                .toList();

        // Topology widget: static infrastructure (Sites + Resources) plus a
        // small "live peers" overlay (recent handshakes only). Sites ordered
        // by name for stable layout across refreshes. Resources keep the same
        // ordering so the same resource always sits at the same angle.
        List<Site> siteRows = Site.<Site>list("order by name");
        Map<String, Integer> portCounts = new HashMap<>();
        // For the topology tooltip we want a compact "TCP 9100, 631"-style
        // string. Fetch protocol + transport + port per resource and assemble
        // the list in-memory; the ordering by transport+port keeps the labels
        // stable across refreshes.
        Map<String, List<String>> portLabels = new HashMap<>();
        if (!siteRows.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> pcRows = em.createQuery(
                    "select rp.resourceId, count(rp) from ResourcePort rp group by rp.resourceId",
                    Object[].class).getResultList();
            for (Object[] r : pcRows) portCounts.put((String) r[0], ((Long) r[1]).intValue());

            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createQuery(
                    "select rp.resourceId, rp.transport, rp.port, rp.protocol "
                    + "from ResourcePort rp order by rp.resourceId, rp.transport, rp.port",
                    Object[].class).getResultList();
            for (Object[] row : portRows) {
                String resId = (String) row[0];
                String transport = ((String) row[1]).toUpperCase();
                int port = (Integer) row[2];
                String protocol = (String) row[3];
                String label = protocol == null || protocol.isBlank() || "CUSTOM".equalsIgnoreCase(protocol)
                        ? transport + " " + port
                        : protocol + " " + port;
                portLabels.computeIfAbsent(resId, k -> new ArrayList<>()).add(label);
            }
        }
        Map<String, Integer> resCountBySite = new HashMap<>();
        List<de.chriscohnen.islandr.acl.Resource> allResources =
                de.chriscohnen.islandr.acl.Resource.<de.chriscohnen.islandr.acl.Resource>list(
                        "order by siteId, name");
        for (de.chriscohnen.islandr.acl.Resource r : allResources) {
            resCountBySite.merge(r.siteId, 1, Integer::sum);
        }
        List<DashboardDto.TopologySite> topoSites = siteRows.stream()
                .map(site -> new DashboardDto.TopologySite(
                        site.id, site.name, site.cidr,
                        resCountBySite.getOrDefault(site.id, 0)))
                .toList();
        int resourceOverflow = Math.max(0, allResources.size() - TOPOLOGY_RESOURCE_CAP);
        List<DashboardDto.TopologyResource> topoResources = allResources.stream()
                .limit(TOPOLOGY_RESOURCE_CAP)
                .map(r -> new DashboardDto.TopologyResource(
                        r.id, r.siteId, r.name, r.ip, r.type,
                        portLabels.getOrDefault(r.id, List.of()),
                        portCounts.getOrDefault(r.id, 0)))
                .toList();

        // Live peers: only the ones that handshook within the last 5 minutes.
        // Until the activity-poller is wired up this list stays empty by design
        // — and that's the right signal: nothing is talking through the tunnel
        // right now. Capped at 12; a busier hub gets a summary instead.
        Instant liveThreshold = Instant.now().minus(TOPOLOGY_LIVE_WINDOW);
        List<DashboardDto.TopologyLivePeer> livePeers = Peer.<Peer>find(
                        "enabled = ?1 and lastSeenAt is not null and lastSeenAt >= ?2 "
                                + "order by lastSeenAt desc",
                        true, liveThreshold)
                .page(0, 12).list().stream()
                .map(p -> new DashboardDto.TopologyLivePeer(
                        p.id, p.name, p.type, p.assignedIp, p.lastSeenAt))
                .toList();

        DashboardDto.Topology topology = new DashboardDto.Topology(
                topoSites, topoResources, livePeers, resourceOverflow,
                s == null ? null : s.wgServerEndpoint);

        FirewallState fs = FirewallState.get();
        DashboardDto.FirewallStatus firewallStatus = new DashboardDto.FirewallStatus(
                fs.lastStatus, fs.ruleCount, fs.lastOkAt, fs.stderrText);

        return new DashboardDto.Response(
                new DashboardDto.PeerStats(peerTotal, peerEnabled, lastSeen24h),
                new DashboardDto.UserStats(userTotal, userAdmins),
                new DashboardDto.RoleStats(roleTotal, rolesWithGrants),
                new DashboardDto.ResourceStats(siteTotal, resTotal, portTotal),
                setup, firewallStatus, audit, peers, topology);
    }
}
