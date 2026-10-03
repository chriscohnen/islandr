package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.ReservationService;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.List;

/**
 * peer-self-share: lets a peer's owner hand one of their own ports to a
 * single named colleague, without ever touching the ACL matrix. Deliberately
 * does not create or modify a {@link de.chriscohnen.islandr.acl.RoleResourceGrant}
 * — {@link de.chriscohnen.islandr.firewall.RuleBuilder} reads live
 * {@link PeerSelfShare} rows directly, the same way it already reads
 * {@link de.chriscohnen.islandr.acl.ResourceReservation} (#72), so creating
 * one here is the entire mechanism; there is no second "apply" step.
 */
@ApplicationScoped
public class PeerSelfShareService {

    @Inject SettingsService settings;

    @Transactional
    public PeerSelfShare create(String ownerPeerId, String callerUserId, String targetEmail, int port, int durationMinutes) {
        if (!settings.get().peerSelfShareEnabled) {
            throw new ForbiddenException("peer-to-peer sharing is disabled by the administrator");
        }
        if (!ReservationService.DURATION_CHOICES.contains(durationMinutes)) {
            throw new BadRequestException("unsupported duration");
        }
        Peer owner = Peer.findById(ownerPeerId);
        if (owner == null || !callerUserId.equals(owner.userId)) {
            throw new NotFoundException("peer not found: " + ownerPeerId);
        }

        User target = User.find("lower(email) = ?1", targetEmail.trim().toLowerCase(java.util.Locale.ROOT)).firstResult();
        if (target == null) {
            throw new BadRequestException("no user with that e-mail address");
        }
        if (target.id.equals(callerUserId)) {
            // Sharing a port on your own device with yourself is meaningless —
            // your own peers already reach it without this mechanism existing.
            throw new BadRequestException("can't share with yourself");
        }

        PeerSelfShare share = PeerSelfShare.createNew(
                ownerPeerId, target.id, port, Instant.now().plusSeconds(durationMinutes * 60L));
        share.persist();
        return share;
    }

    /** Only the owner may see or revoke their own shares — this is a
     *  capability the owner grants, not something visible to the target
     *  (who cannot even see they were named until traffic works). */
    public List<PeerSelfShare> listOwn(String ownerPeerId, String callerUserId) {
        Peer owner = Peer.findById(ownerPeerId);
        if (owner == null || !callerUserId.equals(owner.userId)) {
            throw new NotFoundException("peer not found: " + ownerPeerId);
        }
        return PeerSelfShare.byOwnerPeer(ownerPeerId);
    }

    @Transactional
    public PeerSelfShare revoke(String shareId, String callerUserId) {
        PeerSelfShare share = PeerSelfShare.findById(shareId);
        if (share == null) throw new NotFoundException("share not found: " + shareId);
        Peer owner = Peer.findById(share.ownerPeerId);
        if (owner == null || !callerUserId.equals(owner.userId)) {
            // Same 404-not-403 posture as MyPeerResource#ownedOr404 — a
            // probing caller can't tell "not yours" from "doesn't exist".
            throw new NotFoundException("share not found: " + shareId);
        }
        if (share.revokedAt == null) share.revokedAt = Instant.now();
        return share;
    }
}
