package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.peer.Peer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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

    /**
     * Graph payload for the admin Atlas view: the user's peers, every
     * resource in the site(s) those peers can partially or fully reach
     * (reachable ones flagged, unreachable siblings included as drag
     * targets), and one edge per (peer, resource, contributing role) —
     * deliberately NOT merged across roles like {@link #resolveMyAccess},
     * since Atlas draws a separate line per role.
     */
    public AtlasDto.Graph buildAtlasGraph(String userId) {
        List<Peer> peers = Peer.list("userId = ?1 order by name", userId);
        List<AtlasDto.PeerNode> peerNodes = peers.stream()
                .map(p -> new AtlasDto.PeerNode(p.id, p.name, p.type))
                .toList();
        if (peerNodes.isEmpty()) {
            return new AtlasDto.Graph(peerNodes, List.of(), List.of(), List.of());
        }

        // Roles the user explicitly belongs to (not auto_all) — the grant
        // dialog's choices. An auto_all role is implicit for everyone and not
        // a meaningful "grant under this role" choice for one specific user.
        @SuppressWarnings("unchecked")
        List<Object[]> userRoleRows = em.createNativeQuery(
                        "SELECT r.id, r.name FROM roles r JOIN user_roles ur ON ur.role_id = r.id "
                                + "WHERE ur.user_id = ?1 ORDER BY r.name")
                .setParameter(1, userId)
                .getResultList();
        List<AtlasDto.RoleOption> roleOptions = userRoleRows.stream()
                .map(r -> new AtlasDto.RoleOption((String) r[0], (String) r[1]))
                .toList();

        List<String> roleIds = resolveRoleIds(userId);
        if (roleIds.isEmpty()) {
            return new AtlasDto.Graph(peerNodes, List.of(), List.of(), roleOptions);
        }

        // Concrete grants, kept per-role (not merged).
        @SuppressWarnings("unchecked")
        List<Object[]> grantRows = em.createNativeQuery(
                        "SELECT g.id, g.role_id, r.name, g.resource_id, g.all_ports "
                                + "FROM role_resource_grants g JOIN roles r ON r.id = g.role_id "
                                + "WHERE g.role_id IN ?1")
                .setParameter(1, roleIds)
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

        // Resources reachable via a type-grant — always all-ports, tagged
        // separately since they can't be revoked through role_resource_grants
        // (ADR-0022, "all printers in site X" has no per-resource row).
        @SuppressWarnings("unchecked")
        List<Object[]> typeGrantRows = em.createNativeQuery(
                        "SELECT g.role_id, rl.name, r.id FROM resources r "
                                + "JOIN role_resource_type_grants g ON g.site_id = r.site_id AND g.resource_type = r.type "
                                + "JOIN roles rl ON rl.id = g.role_id "
                                + "WHERE g.role_id IN ?1")
                .setParameter(1, roleIds)
                .getResultList();

        Set<String> grantResourceIds = new HashSet<>();
        for (Object[] row : grantRows) grantResourceIds.add((String) row[3]);
        Set<String> typeGrantResourceIds = new HashSet<>();
        for (Object[] row : typeGrantRows) typeGrantResourceIds.add((String) row[2]);

        Set<String> reachableResourceIds = new HashSet<>(grantResourceIds);
        reachableResourceIds.addAll(typeGrantResourceIds);
        if (reachableResourceIds.isEmpty()) {
            return new AtlasDto.Graph(peerNodes, List.of(), List.of(), roleOptions);
        }

        // Site(s) that own at least one reachable resource — Atlas shows every
        // resource in those sites (reachable + unreachable), not just reachable
        // ones, so an admin can drag a grant onto an unreachable sibling.
        @SuppressWarnings("unchecked")
        List<Object> reachableSiteRows = em.createNativeQuery(
                        "SELECT DISTINCT site_id FROM resources WHERE id IN ?1")
                .setParameter(1, new ArrayList<>(reachableResourceIds))
                .getResultList();
        List<String> siteIds = reachableSiteRows.stream().map(o -> (String) o).toList();

        @SuppressWarnings("unchecked")
        List<Object[]> allResRows = em.createNativeQuery(
                        "SELECT r.id, r.site_id, s.name, r.name, r.type "
                                + "FROM resources r JOIN sites s ON s.id = r.site_id "
                                + "WHERE r.site_id IN ?1 ORDER BY s.name, r.name")
                .setParameter(1, siteIds)
                .getResultList();

        List<AtlasDto.ResourceNode> resourceNodes = new ArrayList<>(allResRows.size());
        for (Object[] r : allResRows) {
            String rid = (String) r[0];
            boolean reachable = reachableResourceIds.contains(rid);
            String ownership = !reachable ? null
                    : grantResourceIds.contains(rid) ? "grant" : "type-grant";
            resourceNodes.add(new AtlasDto.ResourceNode(
                    rid, (String) r[3], (String) r[4], (String) r[1], (String) r[2], reachable, ownership));
        }

        List<AtlasDto.Edge> edges = new ArrayList<>();
        for (Object[] g : grantRows) {
            String grantId = (String) g[0];
            String roleId = (String) g[1];
            String roleName = (String) g[2];
            String resourceId = (String) g[3];
            boolean allPorts = (Boolean) g[4];
            List<String> portLabels = allPorts ? List.of() : portLabelsByGrant.getOrDefault(grantId, List.of());
            for (AtlasDto.PeerNode peer : peerNodes) {
                edges.add(new AtlasDto.Edge(peer.id(), resourceId, roleId, roleName, allPorts, portLabels));
            }
        }
        for (Object[] g : typeGrantRows) {
            String roleId = (String) g[0];
            String roleName = (String) g[1];
            String resourceId = (String) g[2];
            for (AtlasDto.PeerNode peer : peerNodes) {
                edges.add(new AtlasDto.Edge(peer.id(), resourceId, roleId, roleName, true, List.of()));
            }
        }

        return new AtlasDto.Graph(peerNodes, resourceNodes, edges, roleOptions);
    }

    private static String formatPortLabel(int port, Integer portEnd, String protocol) {
        String range = portEnd == null ? String.valueOf(port) : port + "-" + portEnd;
        return (protocol == null || protocol.isBlank()) ? range : protocol + " " + range;
    }
}
