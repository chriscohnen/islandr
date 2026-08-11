package de.chriscohnen.islandr.acl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-grant apply for direct User-Resource grants (ADR-0024) — the same
 * tri-state create/update/delete-if-empty semantics as
 * {@link RoleService#applyMatrix}'s per-cell logic, just for one grant at a
 * time (no batch: the only caller, the Atlas view, always acts on one
 * grant per drag/revoke interaction).
 */
@ApplicationScoped
public class UserGrantService {

    @PersistenceContext EntityManager em;

    public record GrantDiff(String userId, String resourceId, String change,
                            Boolean fromAllPorts, Boolean toAllPorts,
                            List<String> fromPortIds, List<String> toPortIds) {}

    /** All direct user grants, denormalized for the ACL page's list — same shape as the
     * Atlas graph's "user-direct" edges, just without the fan-out/role machinery. */
    public List<UserGrantDto.ListItem> list() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT g.id, g.user_id, u.name, g.resource_id, r.name, s.name, g.all_ports "
                                + "FROM user_resource_grants g "
                                + "JOIN users u ON u.id = g.user_id "
                                + "JOIN resources r ON r.id = g.resource_id "
                                + "JOIN sites s ON s.id = r.site_id "
                                + "ORDER BY u.name, r.name")
                .getResultList();
        if (rows.isEmpty()) return List.of();

        Set<String> limitedGrantIds = new LinkedHashSet<>();
        for (Object[] row : rows) if (!(Boolean) row[6]) limitedGrantIds.add((String) row[0]);
        Map<String, List<String>> portLabelsByGrant = new HashMap<>();
        if (!limitedGrantIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> portRows = em.createNativeQuery(
                            "SELECT gp.grant_id, p.port, p.port_end, p.protocol "
                                    + "FROM user_resource_grant_ports gp "
                                    + "JOIN resource_ports p ON p.id = gp.port_id "
                                    + "WHERE gp.grant_id IN ?1")
                    .setParameter(1, limitedGrantIds)
                    .getResultList();
            for (Object[] p : portRows) {
                String gid = (String) p[0];
                Integer portEnd = p[2] == null ? null : ((Number) p[2]).intValue();
                portLabelsByGrant.computeIfAbsent(gid, k -> new ArrayList<>())
                        .add(AclResolutionService.formatPortLabel(((Number) p[1]).intValue(), portEnd, (String) p[3]));
            }
        }

        List<UserGrantDto.ListItem> out = new ArrayList<>();
        for (Object[] row : rows) {
            String grantId = (String) row[0];
            boolean allPorts = (Boolean) row[6];
            out.add(new UserGrantDto.ListItem(
                    (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                    allPorts, allPorts ? List.of() : portLabelsByGrant.getOrDefault(grantId, List.of())));
        }
        return out;
    }

    /** Returns null when the apply was a no-op (∅ -> ∅ or identical state). */
    @Transactional
    public GrantDiff apply(UserGrantDto.Update u) {
        if (de.chriscohnen.islandr.user.User.findById(u.userId()) == null) {
            throw new NotFoundException("user not found: " + u.userId());
        }
        Resource res = Resource.findById(u.resourceId());
        if (res == null) {
            throw new NotFoundException("resource not found: " + u.resourceId());
        }
        List<String> wantPortIds = u.allPorts() ? List.of()
                : (u.portIds() == null ? List.of() : u.portIds());
        if (!u.allPorts() && !wantPortIds.isEmpty()) {
            ResourceService.validatePortsBelongToResource(res.id, wantPortIds);
        }

        UserResourceGrant g = UserResourceGrant.findByUserResource(u.userId(), u.resourceId());
        boolean wantsNoGrant = !u.allPorts() && wantPortIds.isEmpty();

        if (g == null && wantsNoGrant) {
            return null;
        }
        if (g == null) {
            g = UserResourceGrant.createNew(u.userId(), u.resourceId(), u.allPorts());
            g.persist();
            if (!u.allPorts()) insertPorts(g.id, wantPortIds);
            return new GrantDiff(u.userId(), u.resourceId(), "create", null, u.allPorts(), null, wantPortIds);
        }

        List<String> currentPortIds = currentPortIds(g.id);
        if (wantsNoGrant) {
            deletePortsFor(g.id);
            g.delete();
            return new GrantDiff(u.userId(), u.resourceId(), "delete", g.allPorts, null, currentPortIds, null);
        }

        boolean changed = false;
        if (g.allPorts != u.allPorts()) {
            g.allPorts = u.allPorts();
            changed = true;
        }
        if (!u.allPorts()) {
            Set<String> want = new LinkedHashSet<>(wantPortIds);
            Set<String> have = new LinkedHashSet<>(currentPortIds);
            if (!want.equals(have)) {
                deletePortsFor(g.id);
                insertPorts(g.id, wantPortIds);
                changed = true;
            }
        } else if (!currentPortIds.isEmpty()) {
            deletePortsFor(g.id);
            changed = true;
        }
        if (!changed) return null;
        return new GrantDiff(u.userId(), u.resourceId(), "update", null, u.allPorts(), currentPortIds, wantPortIds);
    }

    private void insertPorts(String grantId, List<String> portIds) {
        for (String pid : portIds) {
            em.createNativeQuery("INSERT INTO user_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                    .setParameter(1, grantId).setParameter(2, pid).executeUpdate();
        }
    }

    private List<String> currentPortIds(String grantId) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(
                        "SELECT port_id FROM user_resource_grant_ports WHERE grant_id = ?1")
                .setParameter(1, grantId)
                .getResultList();
        return ids;
    }

    private void deletePortsFor(String grantId) {
        em.createNativeQuery("DELETE FROM user_resource_grant_ports WHERE grant_id = ?1")
                .setParameter(1, grantId).executeUpdate();
    }
}
