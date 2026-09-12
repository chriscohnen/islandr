package de.chriscohnen.islandr.acl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Zero-trust gate for the IronRDP browser proxy.
 * Resolves the RDP target only when the user has an active grant for the port.
 *
 * <p>The grant decision itself is not made here — it is
 * {@link AclResolutionService#hasPortAccess}, the same resolver the nftables
 * ruleset and the self-service portal answer from. This class used to carry a
 * second copy of the grant SQL that joined {@code user_roles} directly and so
 * never saw the automatic "Everyone" role: the firewall let a user through and
 * the browser session refused them (R-171, issue #83). The duplication was the
 * defect; the missing union was only its symptom.
 */
@ApplicationScoped
public class RdpGrantService {

    @Inject AclResolutionService acl;

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

        if (!localAdmin && !acl.hasPortAccess(userId, port.resourceId, portId)) return null;

        return new RdpTarget(resource.ip, port.port, port.rdpClipboard, port.rdpFileTransfer);
    }

    /** True if the user exists and is an admin. Used to gate {@code ?as=} impersonation. */
    @Transactional
    public boolean isAdmin(String userId) {
        if (userId == null) return false;
        de.chriscohnen.islandr.user.User u = de.chriscohnen.islandr.user.User.findById(userId);
        return u != null && u.isAdmin;
    }

    public record RdpTarget(String host, int port, boolean clipboard, boolean fileTransfer) {}
}
