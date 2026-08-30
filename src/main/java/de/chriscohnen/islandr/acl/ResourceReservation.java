package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One user's claim on an exclusive slot of a capacity-limited <em>port</em>
 * (issue #72).
 *
 * <p>This sits <em>on top of</em> the grant model, it does not replace it: a
 * role or direct grant still decides who may see and request the resource at
 * all, and a reservation decides who holds one of the port's
 * {@link ResourcePort#maxConcurrentUsers} slots right now. Only a port that
 * declares a capacity takes part — for every other port (the default,
 * {@code maxConcurrentUsers == null}) reservations are never created and
 * never consulted, so existing deployments behave exactly as before.
 *
 * <p>Scoped to the port rather than the whole host on purpose: a machine can
 * have a single RDP seat while its SSH port stays freely usable, and one
 * person taking the RDP session should not lock everyone else off the box.
 *
 * <p>Requests at capacity are rejected outright rather than queued: "exclusive"
 * promises the slot you hold, never a turn you are waiting for. The rejection
 * names the current holders and their end times so the caller can go and
 * coordinate — see {@link ReservationService.AtCapacityException}.
 */
@Entity
@Table(name = "resource_reservations")
public class ResourceReservation extends PanacheEntityBase {

    /** Awaiting an admin decision — no access conferred. */
    public static final String PENDING = "pending";
    /** Holds a slot. The only status that confers access, and only until {@link #endsAt}. */
    public static final String ACTIVE = "active";
    /** An admin declined the request. */
    public static final String REJECTED = "rejected";
    /** The holder released it early, or an admin revoked it. */
    public static final String CANCELLED = "cancelled";
    /** Ran to its end time. Distinct from CANCELLED so the audit trail reads honestly. */
    public static final String EXPIRED = "expired";

    @Id @Column(name = "id", nullable = false, length = 36)
    public String id;

    /** The port actually held. This is what capacity is counted against. */
    @Column(name = "port_id", nullable = false, length = 36)
    public String portId;

    /** Denormalised from the port: every enforcement and display path needs the
     *  resource too, and carrying it avoids a join on every ruleset rebuild. */
    @Column(name = "resource_id", nullable = false, length = 36)
    public String resourceId;

    @Column(name = "user_id", nullable = false, length = 36)
    public String userId;

    @Column(name = "status", nullable = false, length = 16)
    public String status;

    /** What the requester asked for; the ceiling is applied before this is stored. */
    @Column(name = "requested_minutes", nullable = false)
    public int requestedMinutes;

    @Column(name = "requested_at", nullable = false)
    public Instant requestedAt;

    /** Null while pending — a reservation has no window until it is actually granted. */
    @Column(name = "starts_at")
    public Instant startsAt;

    @Column(name = "ends_at")
    public Instant endsAt;

    /** Null for auto-approved rows: nobody decided those, and the trail shouldn't imply otherwise. */
    @Column(name = "decided_by", length = 255)
    public String decidedBy;

    @Column(name = "decided_at")
    public Instant decidedAt;

    public static ResourceReservation createPending(String userId, String portId, String resourceId,
                                                    int minutes, Instant now) {
        ResourceReservation r = new ResourceReservation();
        r.id = UUID.randomUUID().toString();
        r.userId = userId;
        r.portId = portId;
        r.resourceId = resourceId;
        r.status = PENDING;
        r.requestedMinutes = minutes;
        r.requestedAt = now;
        return r;
    }

    /** Opens the window. Used both by auto-approve and by an admin approving a pending row. */
    public void activate(Instant now) {
        this.status = ACTIVE;
        this.startsAt = now;
        this.endsAt = now.plusSeconds(requestedMinutes * 60L);
    }

    /** True when this row confers access at {@code now}. */
    public boolean isLiveAt(Instant now) {
        return ACTIVE.equals(status) && endsAt != null && endsAt.isAfter(now);
    }

    /** Active rows on one port — the unit capacity is counted in. */
    public static List<ResourceReservation> activeForPort(String portId) {
        return list("portId = ?1 and status = ?2", portId, ACTIVE);
    }

    public static List<ResourceReservation> openFor(String userId) {
        return list("userId = ?1 and status in ?2", userId, List.of(PENDING, ACTIVE));
    }
}
