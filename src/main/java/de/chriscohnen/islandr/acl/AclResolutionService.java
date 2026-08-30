package de.chriscohnen.islandr.acl;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared ACL resolution: which roles a user belongs to, and which resources
 * those roles grant access to. Used by both the self-service portal
 * ({@link MyAccessResource}) and the admin Atlas view ({@link AtlasResource}) —
 * the two need different shapes of the same underlying grant data (merged
 * per-resource for the portal, kept per-role for Atlas's per-role edges), so
 * only the role-resolution step and the portal's own merged view live here;
 * Atlas's per-role edge-building logic lives in {@code buildAtlasGraph}.
 */
@ApplicationScoped
public class AclResolutionService {

    @PersistenceContext EntityManager em;

    /** Roles a user belongs to: explicit memberships plus every auto_all role
     *  (Everyone), which includes all users implicitly (ADR-0013). */
    @SuppressWarnings("unchecked")
    public List<String> resolveRoleIds(String userId) {
        return em.createNativeQuery(
                        "SELECT role_id FROM user_roles WHERE user_id = ?1 "
                                + "UNION SELECT id FROM roles WHERE auto_all = 1")
                .setParameter(1, userId)
                .getResultList();
    }

    /** Effective per-resource access for the self-service portal — merged
     *  across all of the user's roles, all-ports wins. Moved here verbatim
     *  from {@code MyAccessResource.resolveResources}. */
    public List<ResourceDto.MyAccessResource> resolveMyAccess(String userId) {
        List<String> roleIds = resolveRoleIds(userId);
        if (roleIds.isEmpty()) return List.of();

        @SuppressWarnings("unchecked")
        List<Object[]> grantRows = em.createNativeQuery(
                        "SELECT id, resource_id, all_ports FROM role_resource_grants WHERE role_id IN ?1")
                .setParameter(1, roleIds)
                .getResultList();

        @SuppressWarnings("unchecked")
        List<String> typeGrantResourceIds = em.createNativeQuery(
                        "SELECT r.id FROM resources r " +
                        "JOIN role_resource_type_grants g ON g.site_id = r.site_id AND g.resource_type = r.type " +
                        "WHERE g.role_id IN ?1")
                .setParameter(1, roleIds)
                .getResultList();

        // Direct user grants (ADR-0024) — bypass the role model entirely,
        // so they're keyed on userId directly, not roleIds.
        @SuppressWarnings("unchecked")
        List<Object[]> userGrantRows = em.createNativeQuery(
                        "SELECT id, resource_id, all_ports FROM user_resource_grants WHERE user_id = ?1")
                .setParameter(1, userId)
                .getResultList();

        if (grantRows.isEmpty() && typeGrantResourceIds.isEmpty() && userGrantRows.isEmpty()) return List.of();

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

        Set<String> userGrantIds = new HashSet<>();
        for (Object[] row : userGrantRows) if (!(Boolean) row[2]) userGrantIds.add((String) row[0]);

        Map<String, Set<String>> portsByUserGrant = new HashMap<>();
        if (!userGrantIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createNativeQuery(
                            "SELECT grant_id, port_id FROM user_resource_grant_ports WHERE grant_id IN ?1")
                    .setParameter(1, userGrantIds)
                    .getResultList();
            for (Object[] r : portRows) {
                portsByUserGrant.computeIfAbsent((String) r[0], k -> new HashSet<>()).add((String) r[1]);
            }
        }

        record EffectiveGrant(boolean allPorts, Set<String> portIds) {}
        Map<String, EffectiveGrant> effective = new LinkedHashMap<>();
        for (Object[] row : grantRows) {
            String grantId    = (String)  row[0];
            String resourceId = (String)  row[1];
            boolean allPorts  = (Boolean) row[2];
            EffectiveGrant existing = effective.get(resourceId);
            if (existing != null && existing.allPorts()) continue;
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
        // Direct user grants merge the same way concrete role grants do —
        // union of port sets, all-ports wins — just from a different table.
        for (Object[] row : userGrantRows) {
            String grantId    = (String)  row[0];
            String resourceId = (String)  row[1];
            boolean allPorts  = (Boolean) row[2];
            EffectiveGrant existing = effective.get(resourceId);
            if (existing != null && existing.allPorts()) continue;
            if (allPorts) {
                effective.put(resourceId, new EffectiveGrant(true, Set.of()));
            } else {
                Set<String> ids = portsByUserGrant.getOrDefault(grantId, Set.of());
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

        List<String> resourceIds = new ArrayList<>(effective.keySet());
        @SuppressWarnings("unchecked")
        List<Object[]> resRows = em.createNativeQuery(
                        "SELECT r.id, r.site_id, r.name, r.ip, r.description, r.type, s.name " +
                        "FROM resources r JOIN sites s ON s.id = r.site_id " +
                        "WHERE r.id IN ?1 ORDER BY s.name, r.name")
                .setParameter(1, resourceIds)
                .getResultList();

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

        // Exclusive-capacity state (issue #72), loaded once for the whole
        // list rather than per resource. Resources keep appearing here on the
        // strength of the grant alone — that is the point: the portal must be
        // able to show a reservable resource *before* the user holds a slot,
        // which is what the "On-demand" badge and request button hang off.
        Map<String, Resource> resourceEntities = new HashMap<>();
        for (Resource r : Resource.<Resource>list("id in ?1", resourceIds)) {
            resourceEntities.put(r.id, r);
        }
        Instant now = Instant.now();
        Map<String, ResourceReservation> myOpenByResource = new HashMap<>();
        Map<String, List<ResourceDto.ReservationHolder>> holdersByResource = new HashMap<>();
        List<String> gatedIds = resourceEntities.values().stream()
                .filter(Resource::isCapacityLimited).map(r -> r.id).toList();
        if (!gatedIds.isEmpty()) {
            for (ResourceReservation rr : ResourceReservation.<ResourceReservation>list(
                    "resourceId in ?1 and status in ?2", gatedIds,
                    List.of(ResourceReservation.PENDING, ResourceReservation.ACTIVE))) {
                if (rr.userId.equals(userId)) myOpenByResource.put(rr.resourceId, rr);
                if (rr.isLiveAt(now)) {
                    de.chriscohnen.islandr.user.User holder =
                            de.chriscohnen.islandr.user.User.findById(rr.userId);
                    holdersByResource.computeIfAbsent(rr.resourceId, k -> new ArrayList<>())
                            .add(new ResourceDto.ReservationHolder(rr.userId,
                                    holder == null ? rr.userId : holder.name, rr.endsAt));
                }
            }
        }

        List<ResourceDto.MyAccessResource> out = new ArrayList<>(resRows.size());
        for (Object[] r : resRows) {
            String rid = (String) r[0];
            EffectiveGrant grant = effective.get(rid);
            List<ResourceDto.PortResponse> allPorts = portsByResource.getOrDefault(rid, List.of());
            List<ResourceDto.PortResponse> granted = grant.allPorts()
                    ? allPorts
                    : allPorts.stream().filter(p -> grant.portIds().contains(p.id())).toList();
            Resource entity = resourceEntities.get(rid);
            ResourceReservation mine = myOpenByResource.get(rid);
            out.add(new ResourceDto.MyAccessResource(
                    rid,
                    (String) r[1],
                    (String) r[6],
                    (String) r[2],
                    (String) r[3],
                    (String) r[4],
                    (String) r[5],
                    granted,
                    entity == null ? null : entity.maxConcurrentUsers,
                    entity == null ? null : entity.maxReservationMinutes,
                    entity == null || entity.autoApproveReservations,
                    mine == null ? null : mine.id,
                    mine == null ? null : mine.status,
                    mine == null ? null : mine.endsAt,
                    holdersByResource.getOrDefault(rid, List.of())));
        }
        return out;
    }

    /**
     * Global graph for the admin Atlas view (reframed 2026-08-11 from a
     * per-user view to this global one — see the Atlas user-grants design
     * spec): every User, every Resource across every Site, and one edge per
     * contributing grant. Role/type-grants fan out to every user holding
     * that role; direct user-grants (ADR-0024) are already user-scoped, no
     * fan-out needed.
     */
    public AtlasDto.Graph buildAtlasGraph() {
        List<de.chriscohnen.islandr.user.User> users =
                de.chriscohnen.islandr.user.User.<de.chriscohnen.islandr.user.User>listAll(Sort.by("name"));
        List<AtlasDto.UserNode> userNodes = users.stream()
                .map(u -> new AtlasDto.UserNode(u.id, u.name))
                .toList();

        // Every user's effective role set: explicit user_roles membership
        // plus every auto_all role (Everyone, ADR-0013) — same rule
        // resolveRoleIds applies per-user, computed here for all users at
        // once so role-grants/type-grants can fan out without N+1 queries,
        // and so the frontend's role filter can show true membership
        // (not just "has a grant via this role", which misses roles that
        // hold no resource grant yet).
        @SuppressWarnings("unchecked")
        List<Object[]> membershipRows = em.createNativeQuery(
                        "SELECT user_id, role_id FROM user_roles").getResultList();
        Map<String, Set<String>> roleIdsByUser = new HashMap<>();
        for (Object[] row : membershipRows) {
            roleIdsByUser.computeIfAbsent((String) row[0], k -> new HashSet<>()).add((String) row[1]);
        }
        List<String> autoAllRoleIds = Role.<Role>list("autoAll", true).stream().map(r -> r.id).toList();
        for (de.chriscohnen.islandr.user.User u : users) {
            roleIdsByUser.computeIfAbsent(u.id, k -> new HashSet<>()).addAll(autoAllRoleIds);
        }
        Map<String, List<String>> usersByRole = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : roleIdsByUser.entrySet()) {
            for (String roleId : e.getValue()) {
                usersByRole.computeIfAbsent(roleId, k -> new ArrayList<>()).add(e.getKey());
            }
        }

        List<AtlasDto.RoleOption> roleOptions = Role.<Role>listAll(Sort.by("name")).stream()
                .map(r -> new AtlasDto.RoleOption(r.id, r.name, usersByRole.getOrDefault(r.id, List.of())))
                .toList();

        // Peer name is joined in (not just the id) so the Atlas frontend can label the
        // gateway diamond and drive the "ping the site peer itself" diagnostics action
        // (ADR-0025) without a second round-trip per site.
        @SuppressWarnings("unchecked")
        List<Object[]> siteRows = em.createNativeQuery(
                        "SELECT s.id, s.name, s.cidr, s.gateway_peer_id, p.name "
                                + "FROM sites s LEFT JOIN peers p ON p.id = s.gateway_peer_id "
                                + "ORDER BY s.name")
                .getResultList();
        List<AtlasDto.SiteNode> siteNodes = siteRows.stream()
                .map(s -> new AtlasDto.SiteNode((String) s[0], (String) s[1], (String) s[2], (String) s[3], (String) s[4]))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> allResRows = em.createNativeQuery(
                        "SELECT r.id, r.site_id, s.name, r.name, r.type, s.cidr, r.ip, r.description, "
                                + "s.gateway_peer_id, gw.name "
                                + "FROM resources r JOIN sites s ON s.id = r.site_id "
                                + "LEFT JOIN peers gw ON gw.id = s.gateway_peer_id "
                                + "ORDER BY s.name, r.name")
                .getResultList();
        List<AtlasDto.ResourceNode> resourceNodes = allResRows.stream()
                .map(r -> new AtlasDto.ResourceNode(
                        (String) r[0], (String) r[3], (String) r[4], (String) r[1], (String) r[2],
                        (String) r[5], (String) r[6], (String) r[7], (String) r[8], (String) r[9]))
                .toList();

        // Only resources are a hard requirement for any edge to exist — site-direct
        // grants don't need a single User row, so an empty userNodes list alone
        // must not suppress them (the role/type/user-direct blocks below degrade
        // to zero edges gracefully on their own when there are no users).
        String[] hubIps = hubIps();

        if (resourceNodes.isEmpty()) {
            return new AtlasDto.Graph(userNodes, resourceNodes, List.of(), roleOptions, siteNodes,
                    hubIps[0], hubIps[1]);
        }

        List<AtlasDto.Edge> edges = new ArrayList<>();

        // Role grants, fanned out to every user holding that role.
        @SuppressWarnings("unchecked")
        List<Object[]> grantRows = em.createNativeQuery(
                        "SELECT g.id, g.role_id, rl.name, g.resource_id, g.all_ports "
                                + "FROM role_resource_grants g JOIN roles rl ON rl.id = g.role_id")
                .getResultList();
        Set<String> limitedGrantIds = new HashSet<>();
        for (Object[] row : grantRows) if (!(Boolean) row[4]) limitedGrantIds.add((String) row[0]);
        Map<String, List<String>> portLabelsByGrant = new HashMap<>();
        if (!limitedGrantIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createNativeQuery(
                            "SELECT gp.grant_id, p.port, p.port_end, p.protocol "
                                    + "FROM role_resource_grant_ports gp "
                                    + "JOIN resource_ports p ON p.id = gp.port_id "
                                    + "WHERE gp.grant_id IN ?1")
                    .setParameter(1, limitedGrantIds)
                    .getResultList();
            for (Object[] p : portRows) {
                String gid = (String) p[0];
                Integer portEnd = p[2] == null ? null : ((Number) p[2]).intValue();
                portLabelsByGrant.computeIfAbsent(gid, k -> new ArrayList<>())
                        .add(formatPortLabel(((Number) p[1]).intValue(), portEnd, (String) p[3]));
            }
        }
        for (Object[] g : grantRows) {
            String grantId = (String) g[0], roleId = (String) g[1], roleName = (String) g[2],
                   resourceId = (String) g[3];
            boolean allPorts = (Boolean) g[4];
            List<String> portLabels = allPorts ? List.of() : portLabelsByGrant.getOrDefault(grantId, List.of());
            for (String userId : usersByRole.getOrDefault(roleId, List.of())) {
                edges.add(new AtlasDto.Edge("user", userId, resourceId, "role", roleId, roleName, allPorts, portLabels));
            }
        }

        // Type grants, same fan-out, always all-ports.
        @SuppressWarnings("unchecked")
        List<Object[]> typeGrantRows = em.createNativeQuery(
                        "SELECT g.role_id, rl.name, r.id FROM role_resource_type_grants g "
                                + "JOIN roles rl ON rl.id = g.role_id "
                                + "JOIN resources r ON r.site_id = g.site_id AND r.type = g.resource_type")
                .getResultList();
        for (Object[] g : typeGrantRows) {
            String roleId = (String) g[0], roleName = (String) g[1], resourceId = (String) g[2];
            for (String userId : usersByRole.getOrDefault(roleId, List.of())) {
                edges.add(new AtlasDto.Edge("user", userId, resourceId, "type-grant", roleId, roleName, true, List.of()));
            }
        }

        // Direct user grants (ADR-0024) — already user-scoped, no fan-out.
        @SuppressWarnings("unchecked")
        List<Object[]> userGrantRows = em.createNativeQuery(
                        "SELECT id, user_id, resource_id, all_ports FROM user_resource_grants")
                .getResultList();
        Set<String> limitedUserGrantIds = new HashSet<>();
        for (Object[] row : userGrantRows) if (!(Boolean) row[3]) limitedUserGrantIds.add((String) row[0]);
        Map<String, List<String>> portLabelsByUserGrant = new HashMap<>();
        if (!limitedUserGrantIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createNativeQuery(
                            "SELECT gp.grant_id, p.port, p.port_end, p.protocol "
                                    + "FROM user_resource_grant_ports gp "
                                    + "JOIN resource_ports p ON p.id = gp.port_id "
                                    + "WHERE gp.grant_id IN ?1")
                    .setParameter(1, limitedUserGrantIds)
                    .getResultList();
            for (Object[] p : portRows) {
                String gid = (String) p[0];
                Integer portEnd = p[2] == null ? null : ((Number) p[2]).intValue();
                portLabelsByUserGrant.computeIfAbsent(gid, k -> new ArrayList<>())
                        .add(formatPortLabel(((Number) p[1]).intValue(), portEnd, (String) p[3]));
            }
        }
        for (Object[] g : userGrantRows) {
            String grantId = (String) g[0], userId = (String) g[1], resourceId = (String) g[2];
            boolean allPorts = (Boolean) g[3];
            List<String> portLabels = allPorts ? List.of() : portLabelsByUserGrant.getOrDefault(grantId, List.of());
            edges.add(new AtlasDto.Edge("user", userId, resourceId, "user-direct", null, null, allPorts, portLabels));
        }

        // Direct site grants — already site-scoped, no fan-out, symmetric to
        // the user-direct block above but keyed by siteId instead of userId.
        @SuppressWarnings("unchecked")
        List<Object[]> siteGrantRows = em.createNativeQuery(
                        "SELECT id, site_id, resource_id, all_ports FROM site_resource_grants")
                .getResultList();
        Set<String> limitedSiteGrantIds = new HashSet<>();
        for (Object[] row : siteGrantRows) if (!(Boolean) row[3]) limitedSiteGrantIds.add((String) row[0]);
        Map<String, List<String>> portLabelsBySiteGrant = new HashMap<>();
        if (!limitedSiteGrantIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createNativeQuery(
                            "SELECT gp.grant_id, p.port, p.port_end, p.protocol "
                                    + "FROM site_resource_grant_ports gp "
                                    + "JOIN resource_ports p ON p.id = gp.port_id "
                                    + "WHERE gp.grant_id IN ?1")
                    .setParameter(1, limitedSiteGrantIds)
                    .getResultList();
            for (Object[] p : portRows) {
                String gid = (String) p[0];
                Integer portEnd = p[2] == null ? null : ((Number) p[2]).intValue();
                portLabelsBySiteGrant.computeIfAbsent(gid, k -> new ArrayList<>())
                        .add(formatPortLabel(((Number) p[1]).intValue(), portEnd, (String) p[3]));
            }
        }
        for (Object[] g : siteGrantRows) {
            String grantId = (String) g[0], siteId = (String) g[1], resourceId = (String) g[2];
            boolean allPorts = (Boolean) g[3];
            List<String> portLabels = allPorts ? List.of() : portLabelsBySiteGrant.getOrDefault(grantId, List.of());
            edges.add(new AtlasDto.Edge("site", siteId, resourceId, "site-direct", null, null, allPorts, portLabels));
        }

        return new AtlasDto.Graph(userNodes, resourceNodes, edges, roleOptions, siteNodes,
                hubIps[0], hubIps[1]);
    }

    /**
     * The Hub's own tunnel address(es) — network+1 of wgSubnet/wgSubnet6, the same
     * "server address" convention {@link de.chriscohnen.islandr.settings.Settings#effectiveClientDns()}
     * already relies on. Returns {@code [ip4, ip6]}; either slot is null if the
     * corresponding subnet is unset or fails to parse (IPv6 is optional; an
     * unparseable wgSubnet shouldn't be possible — it's {@code @ValidCidr}-enforced
     * on save — but the Atlas graph must never fail to load over it).
     */
    private String[] hubIps() {
        de.chriscohnen.islandr.settings.Settings s =
                de.chriscohnen.islandr.settings.Settings.findById(de.chriscohnen.islandr.settings.Settings.SINGLETON_ID);
        String ip4 = null, ip6 = null;
        try {
            ip4 = de.chriscohnen.islandr.peer.IpSubnet.parse(s.wgSubnet).networkAddress();
        } catch (RuntimeException ignored) { /* graph must load regardless */ }
        if (s.wgSubnet6 != null && !s.wgSubnet6.isBlank()) {
            try {
                ip6 = de.chriscohnen.islandr.peer.IpSubnet.parse(s.wgSubnet6).networkAddress();
            } catch (RuntimeException ignored) { /* IPv6 is optional anyway */ }
        }
        return new String[] { ip4, ip6 };
    }

    static String formatPortLabel(int port, Integer portEnd, String protocol) {
        String range = portEnd == null ? String.valueOf(port) : port + "-" + portEnd;
        return (protocol == null || protocol.isBlank()) ? range : protocol + " " + range;
    }
}
