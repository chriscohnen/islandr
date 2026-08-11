package de.chriscohnen.islandr.acl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.LinkedHashSet;
import java.util.List;
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
