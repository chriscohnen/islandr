package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @GET
    public List<ResourceDto.MyAccessResource> myResources(
            @Context ContainerRequestContext ctx,
            @QueryParam("userId") String userIdParam) {

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

        // 1. Roles this user belongs to.
        @SuppressWarnings("unchecked")
        List<String> roleIds = em.createNativeQuery(
                        "SELECT role_id FROM user_roles WHERE user_id = ?1")
                .setParameter(1, userId)
                .getResultList();
        if (roleIds.isEmpty()) return List.of();

        // 2. Grants for those roles.
        @SuppressWarnings("unchecked")
        List<Object[]> grantRows = em.createNativeQuery(
                        "SELECT id, resource_id, all_ports FROM role_resource_grants WHERE role_id IN ?1")
                .setParameter(1, roleIds)
                .getResultList();
        if (grantRows.isEmpty()) return List.of();

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
