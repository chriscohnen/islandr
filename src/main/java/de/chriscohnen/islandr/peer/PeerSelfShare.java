package de.chriscohnen.islandr.peer;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A peer owner's own, narrower-than-ACL grant of one port to a single named
 * colleague (peer-self-share) — "share port 3000 on my machine with
 * Colleague". Deliberately not a {@link de.chriscohnen.islandr.acl.RoleResourceGrant}
 * or any other ACL row: the whole point is that a non-admin user can create
 * one of these without ever touching the ACL matrix, which stays strictly an
 * admin concern. {@link de.chriscohnen.islandr.firewall.RuleBuilder} reads
 * active rows as an additional input when rendering the ruleset, the same way
 * it already reads {@link de.chriscohnen.islandr.acl.ResourceReservation}
 * (#72) — never by synthesizing a grant.
 *
 * <p>Always time-limited ({@link #validUntil} is never null) and always
 * scoped to the owner's own tunnel address — see {@code PeerSelfShareService}
 * for the write-time constraints (port &gt; 1024, target user must exist,
 * owner must be the caller's own peer) this entity itself does not enforce.
 */
@Entity
@Table(name = "peer_self_shares")
public class PeerSelfShare extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    /** The peer whose port is being shared — must belong to the user who
     *  created this row (enforced by the service, not here). */
    @Column(name = "owner_peer_id", nullable = false, length = 36)
    public String ownerPeerId;

    /** The single named colleague who gets access — every one of their
     *  peers reaches the owner's port, the same "grant reaches the user,
     *  not one device" shape the rest of the ACL model already uses. */
    @Column(name = "target_user_id", nullable = false, length = 36)
    public String targetUserId;

    @Column(name = "port", nullable = false)
    public int port;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "valid_until", nullable = false)
    public Instant validUntil;

    /** Set when the owner revokes early. Null = still standing (subject to
     *  {@link #validUntil} regardless). */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    public static PeerSelfShare createNew(String ownerPeerId, String targetUserId, int port, Instant validUntil) {
        PeerSelfShare s = new PeerSelfShare();
        s.id = UUID.randomUUID().toString();
        s.ownerPeerId = ownerPeerId;
        s.targetUserId = targetUserId;
        s.port = port;
        s.createdAt = Instant.now();
        s.validUntil = validUntil;
        return s;
    }

    /** True when this row confers access at {@code now} — the single
     *  condition {@link de.chriscohnen.islandr.firewall.RuleBuilder} checks
     *  before rendering a rule for it. */
    public boolean isLiveAt(Instant now) {
        return revokedAt == null && validUntil.isAfter(now);
    }

    /** Every share (live or not) a peer owns — the "my shares" list in the
     *  self-service portal shows expired/revoked rows too, greyed out,
     *  rather than making them disappear the moment they stop working. */
    public static List<PeerSelfShare> byOwnerPeer(String ownerPeerId) {
        return list("ownerPeerId", ownerPeerId);
    }

    public static List<PeerSelfShare> live(Instant now) {
        return list("revokedAt is null and validUntil > ?1", now);
    }

    /** Count of live shares per owner peer, for a whole device list in one
     *  query — "Mein Zugang" needs this to show a share count per device
     *  without opening the share dialog, for every peer at once rather than
     *  once per row. Peers with zero live shares are simply absent from the
     *  returned map rather than present with a zero. */
    public static java.util.Map<String, Long> liveCountsByOwnerPeerIds(List<String> ownerPeerIds, Instant now) {
        if (ownerPeerIds.isEmpty()) return java.util.Map.of();
        List<PeerSelfShare> rows = list(
                "ownerPeerId in ?1 and revokedAt is null and validUntil > ?2", ownerPeerIds, now);
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        for (PeerSelfShare s : rows) {
            counts.merge(s.ownerPeerId, 1L, Long::sum);
        }
        return counts;
    }
}
