package de.chriscohnen.islandr.acl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

/**
 * Resource-level (not port-level) access check: does this user have *any*
 * grant on this resource — a concrete {@link RoleResourceGrant} or a
 * type-based {@link RoleResourceTypeGrant} (ADR-0022) — through any role
 * they belong to, including "Everyone" auto-all roles (ADR-0013)?
 *
 * <p>Same role-resolution semantics as {@code MyAccessResource.resolveResources}
 * and {@link RdpGrantService}, but resource-scoped and port-agnostic — used by
 * the DNS resolver (ADR-0023), which answers or refuses per resource
 * regardless of which port a peer might eventually connect on.
 */
@ApplicationScoped
public class AclService {

    @Inject EntityManager em;

    public boolean hasAnyGrant(String userId, String resourceId) {
        if (userId == null || resourceId == null) return false;
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT COUNT(*) FROM resources r WHERE r.id = ?2 AND (" +
                "  EXISTS (SELECT 1 FROM role_resource_grants g " +
                "          JOIN user_roles ur ON ur.role_id = g.role_id " +
                "          WHERE ur.user_id = ?1 AND g.resource_id = r.id)" +
                "  OR EXISTS (SELECT 1 FROM role_resource_grants g " +
                "          JOIN roles ro ON ro.id = g.role_id AND ro.auto_all = 1 " +
                "          WHERE g.resource_id = r.id)" +
                "  OR EXISTS (SELECT 1 FROM role_resource_type_grants g " +
                "          JOIN user_roles ur ON ur.role_id = g.role_id " +
                "          WHERE ur.user_id = ?1 AND g.site_id = r.site_id AND g.resource_type = r.type)" +
                "  OR EXISTS (SELECT 1 FROM role_resource_type_grants g " +
                "          JOIN roles ro ON ro.id = g.role_id AND ro.auto_all = 1 " +
                "          WHERE g.site_id = r.site_id AND g.resource_type = r.type)" +
                ")")
                .setParameter(1, userId)
                .setParameter(2, resourceId)
                .getResultList();
        return !rows.isEmpty() && rows.get(0).intValue() > 0;
    }
}
