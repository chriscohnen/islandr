package de.chriscohnen.islandr.acl;

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

        if (grantRows.isEmpty() && typeGrantResourceIds.isEmpty()) return List.of();

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
}
