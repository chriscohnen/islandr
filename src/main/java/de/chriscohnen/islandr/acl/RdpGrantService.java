package de.chriscohnen.islandr.acl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Zero-trust gate for the IronRDP browser proxy.
 * Resolves the RDP target only when the user has an active grant for the port.
 */
@ApplicationScoped
public class RdpGrantService {

    @Inject EntityManager em;

    /**
     * Returns the TCP target to connect to if the session is authorised,
     * or {@code null} if the port does not exist, is not RDP, or access is denied.
     *
     * @param localAdmin true when the session belongs to the ENV-bootstrapped admin
     *                   (bypasses ACL check, still requires port to be RDP)
     */
    @Transactional
    public RdpTarget resolveTarget(String portId, String userId, boolean localAdmin) {
        ResourcePort port = ResourcePort.findById(portId);
        if (port == null || !"RDP".equalsIgnoreCase(port.protocol)) return null;

        Resource resource = Resource.findById(port.resourceId);
        if (resource == null) return null;

        if (!localAdmin && !hasGrant(userId, port.resourceId, portId)) return null;

        return new RdpTarget(resource.ip, port.port, port.rdpClipboard, port.rdpFileTransfer);
    }

    /** True if the user exists and is an admin. Used to gate {@code ?as=} impersonation. */
    @Transactional
    public boolean isAdmin(String userId) {
        if (userId == null) return false;
        de.chriscohnen.islandr.user.User u = de.chriscohnen.islandr.user.User.findById(userId);
        return u != null && u.isAdmin;
    }

    private boolean hasGrant(String userId, String resourceId, String portId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT COUNT(*) FROM role_resource_grants g " +
                "JOIN user_roles ur ON ur.role_id = g.role_id " +
                "WHERE ur.user_id = ?1 AND g.resource_id = ?2 " +
                "AND (g.all_ports = TRUE OR EXISTS (" +
                "  SELECT 1 FROM role_resource_grant_ports rgp " +
                "  WHERE rgp.grant_id = g.id AND rgp.port_id = ?3" +
                "))")
            .setParameter(1, userId)
            .setParameter(2, resourceId)
            .setParameter(3, portId)
            .getResultList();
        if (!rows.isEmpty() && rows.get(0).intValue() > 0) return true;
        if (hasTypeGrant(userId, resourceId)) return true;
        if (hasNetworkGrant(userId, resourceId)) return true;
        return hasDirectUserGrant(userId, resourceId, portId);
    }

    // Type grants ("all printers in Homeoffice") are always all-ports, so a
    // matching resource — same site, same resource_type as the grant — is
    // enough; no port-level check needed, unlike the concrete-grant path above.
    private boolean hasTypeGrant(String userId, String resourceId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT COUNT(*) FROM role_resource_type_grants g " +
                "JOIN user_roles ur ON ur.role_id = g.role_id " +
                "JOIN resources r ON r.id = ?2 AND r.site_id = g.site_id AND r.type = g.resource_type " +
                "WHERE ur.user_id = ?1")
            .setParameter(1, userId)
            .setParameter(2, resourceId)
            .getResultList();
        return !rows.isEmpty() && rows.get(0).intValue() > 0;
    }

    // Network grants (#78, ADR-0029) are always full-reach, so a matching
    // resource — same site as the grant — is enough, no port-level check
    // needed, same reasoning as hasTypeGrant just above. Same R-171 gap as
    // hasTypeGrant (no auto_all union) — inherited, not introduced, by this
    // check; see ADR-0029's Consequences.
    private boolean hasNetworkGrant(String userId, String resourceId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT COUNT(*) FROM role_network_grants g " +
                "JOIN user_roles ur ON ur.role_id = g.role_id " +
                "JOIN resources r ON r.id = ?2 AND r.site_id = g.site_id " +
                "WHERE ur.user_id = ?1")
            .setParameter(1, userId)
            .setParameter(2, resourceId)
            .getResultList();
        return !rows.isEmpty() && rows.get(0).intValue() > 0;
    }

    // Direct user grants (ADR-0024) — same shape as the concrete role-grant
    // check above, just against user_resource_grants directly (no
    // user_roles join needed, the grant already names the user).
    private boolean hasDirectUserGrant(String userId, String resourceId, String portId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT COUNT(*) FROM user_resource_grants g " +
                "WHERE g.user_id = ?1 AND g.resource_id = ?2 " +
                "AND (g.all_ports = TRUE OR EXISTS (" +
                "  SELECT 1 FROM user_resource_grant_ports ugp " +
                "  WHERE ugp.grant_id = g.id AND ugp.port_id = ?3" +
                "))")
            .setParameter(1, userId)
            .setParameter(2, resourceId)
            .setParameter(3, portId)
            .getResultList();
        return !rows.isEmpty() && rows.get(0).intValue() > 0;
    }

    public record RdpTarget(String host, int port, boolean clipboard, boolean fileTransfer) {}
}
