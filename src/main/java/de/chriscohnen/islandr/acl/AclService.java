package de.chriscohnen.islandr.acl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
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
                // Direct User→Resource grant (ADR-0024) — bypasses the role
                // model entirely, so it's keyed on userId directly, no
                // user_roles join needed. This EXISTS clause was missing
                // here even though RdpGrantService and MyAccessResource's
                // resolveMyAccess both already check it: a resource granted
                // *only* this way (the ACL matrix's "Freigabe hinzufügen"
                // dialog) looked identical to "no grant at all" to callers
                // of this method, e.g. the DNS resolver (ADR-0023).
                "  OR EXISTS (SELECT 1 FROM user_resource_grants g " +
                "          WHERE g.user_id = ?1 AND g.resource_id = r.id)" +
                ")")
                .setParameter(1, userId)
                .setParameter(2, resourceId)
                .getResultList();
        return !rows.isEmpty() && rows.get(0).intValue() > 0;
    }

    /**
     * Reservation-aware access check (issue #72): eligibility
     * ({@link #hasAnyGrant}) <em>and</em>, when the resource has any
     * capacity-limited port, a live {@link ResourceReservation} on at least
     * one port the caller could use.
     *
     * <p>Answers a resource-level question ("should this name resolve, is this
     * host reachable at all"), which is what the DNS resolver needs. Capacity
     * itself is per port, so a host whose SSH port is open and whose RDP port
     * is taken still resolves — only a host where <em>every</em> port is gated
     * and unheld does not.
     *
     * <p>{@link #hasAnyGrant} on its own answers only "is this user allowed to
     * ask for it", which is what {@link ReservationService#request} needs and
     * nothing else should use to gate actual reachability.
     */
    public boolean canReachNow(String userId, String resourceId) {
        if (!hasAnyGrant(userId, resourceId)) return false;
        List<ResourcePort> ports = ResourcePort.list("resourceId", resourceId);
        if (ports.isEmpty()) return true;   // nothing gated, nothing to check

        Instant now = Instant.now();
        boolean anyGated = false;
        for (ResourcePort port : ports) {
            if (!port.isCapacityLimited()) return true;   // an open port is enough
            anyGated = true;
            for (ResourceReservation r : ResourceReservation.<ResourceReservation>list(
                    "userId = ?1 and portId = ?2 and status = ?3",
                    userId, port.id, ResourceReservation.ACTIVE)) {
                if (r.isLiveAt(now)) return true;
            }
        }
        return !anyGated;
    }

    /** Per-port variant — "may this user actually use this specific port right now". */
    public boolean canReachPortNow(String userId, ResourcePort port) {
        if (port == null) return false;
        if (!hasAnyGrant(userId, port.resourceId)) return false;
        if (!port.isCapacityLimited()) return true;
        Instant now = Instant.now();
        for (ResourceReservation r : ResourceReservation.<ResourceReservation>list(
                "userId = ?1 and portId = ?2 and status = ?3",
                userId, port.id, ResourceReservation.ACTIVE)) {
            if (r.isLiveAt(now)) return true;
        }
        return false;
    }
}
