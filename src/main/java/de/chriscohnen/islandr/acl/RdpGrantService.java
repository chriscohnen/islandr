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
        return !rows.isEmpty() && rows.get(0).intValue() > 0;
    }

    public record RdpTarget(String host, int port, boolean clipboard, boolean fileTransfer) {}
}
