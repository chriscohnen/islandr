package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reservation half of issue #72 — who holds an exclusive slot on a
 * capacity-limited <em>port</em> right now.
 *
 * <p>Deliberately does not touch the grant model. A grant answers "may this
 * user request this resource at all"; this service answers "does this user
 * hold one of the port's slots". {@link RuleBuilder} consults both before
 * emitting a rule, which is what makes the reservation *mandatory* rather
 * than an alternative route to access.
 *
 * <p>Capacity is counted per port, not per host: one seat on RDP must not
 * take SSH on the same machine down with it.
 *
 * <p>Requests that do not fit are rejected outright, never queued
 * ({@link AtCapacityException} carries the current holders so the caller can
 * be told who to go and ask). The alternative — a waitlist — would promise a
 * turn that "exclusive" cannot honestly guarantee.
 */
@ApplicationScoped
public class ReservationService {

    /** Offered durations, in minutes — the same ladder #70's admin grant dropdown uses. */
    public static final List<Integer> DURATION_CHOICES = List.of(60, 240, 1440, 10080);

    @Inject AclService acl;

    /**
     * One current holder of a slot. Carries the e-mail as well as the name so
     * a rejected requester can actually go and ask them — the whole point of
     * naming holders instead of returning a bare "taken".
     */
    public record Holder(String userId, String userName, String userEmail, Instant until) {}

    /**
     * Thrown when every slot is taken. Carries the holders deliberately
     * un-anonymised: the point of the rejection is coordination ("go ask
     * Jane, or wait until 14:30"), which a bare "no" cannot support.
     */
    public static class AtCapacityException extends RuntimeException {
        public final transient List<Holder> holders;
        public AtCapacityException(List<Holder> holders) {
            super("resource is at capacity");
            this.holders = holders;
        }
    }

    /**
     * Requests a slot. Returns the row, either {@code ACTIVE} (auto-approved,
     * capacity was free) or {@code PENDING} (the resource requires an admin
     * decision).
     *
     * @throws AtCapacityException when no slot is free — never queued.
     */
    @Transactional
    public ResourceReservation request(String userId, String portId, int minutes) {
        Instant now = Instant.now();
        ResourcePort port = ResourcePort.findById(portId);
        if (port == null) throw new NotFoundException("port not found");
        Resource res = Resource.findById(port.resourceId);
        if (res == null) throw new NotFoundException("resource not found");

        if (!port.isCapacityLimited()) {
            // Nothing to reserve: an unlimited port is reachable on the
            // strength of the grant alone, so handing out a reservation would
            // imply an exclusivity the port does not actually have.
            throw new BadRequestException("port is not reservable");
        }
        // Checked before anything computes or reports holders: an ineligible
        // caller must not be able to learn who is using a port by probing it.
        // hasAnyGrant is the eligibility half only — deliberately NOT the
        // reservation-aware canReachNow, which would make requesting a slot
        // require already holding one.
        if (!acl.hasAnyGrant(userId, res.id)) {
            throw new ForbiddenException("no grant for this resource");
        }
        if (!DURATION_CHOICES.contains(minutes)) {
            throw new BadRequestException("unsupported duration");
        }
        int effectiveMinutes = capToPortCeiling(port, minutes);

        expireDueForPort(portId, now);

        for (ResourceReservation existing : ResourceReservation.openFor(userId)) {
            if (existing.portId.equals(portId)) {
                throw new BadRequestException("you already hold or have requested this port");
            }
        }

        List<ResourceReservation> active = liveReservations(portId, now);
        if (active.size() >= port.maxConcurrentUsers) throw new AtCapacityException(holdersOf(active));

        ResourceReservation r = ResourceReservation.createPending(userId, portId, res.id, effectiveMinutes, now);
        if (port.autoApproveReservations) r.activate(now);
        r.persist();
        return r;
    }

    /**
     * The duration ceiling is applied by trimming, not by rejecting: the
     * picker offers a fixed ladder, and a resource capped at 4h should hand a
     * 24h request a 4h slot rather than an error the user cannot act on.
     */
    static int capToPortCeiling(ResourcePort port, int requestedMinutes) {
        if (port.maxReservationMinutes == null) return requestedMinutes;
        return Math.min(requestedMinutes, port.maxReservationMinutes);
    }

    /** Releases a slot early. Only the holder may do this; admins use {@link #revoke}. */
    @Transactional
    public ResourceReservation cancelOwn(String userId, String reservationId) {
        ResourceReservation r = ResourceReservation.findById(reservationId);
        if (r == null) throw new NotFoundException("reservation not found");
        if (!r.userId.equals(userId)) throw new ForbiddenException("not your reservation");
        if (!ResourceReservation.PENDING.equals(r.status) && !ResourceReservation.ACTIVE.equals(r.status)) {
            throw new BadRequestException("reservation is not open");
        }
        r.status = ResourceReservation.CANCELLED;
        r.endsAt = Instant.now();
        return r;
    }

    /** Admin override — same terminal state as a self-release, different actor. */
    @Transactional
    public ResourceReservation revoke(String reservationId, String actor) {
        ResourceReservation r = ResourceReservation.findById(reservationId);
        if (r == null) throw new NotFoundException("reservation not found");
        if (!ResourceReservation.PENDING.equals(r.status) && !ResourceReservation.ACTIVE.equals(r.status)) {
            throw new BadRequestException("reservation is not open");
        }
        r.status = ResourceReservation.CANCELLED;
        r.endsAt = Instant.now();
        r.decidedBy = actor;
        r.decidedAt = Instant.now();
        return r;
    }

    /**
     * Approves a pending request. Re-checks capacity at decision time rather
     * than trusting the check made when the request was filed — an admin may
     * be looking at a queue built up while slots filled.
     */
    @Transactional
    public ResourceReservation approve(String reservationId, String actor) {
        Instant now = Instant.now();
        ResourceReservation r = ResourceReservation.findById(reservationId);
        if (r == null) throw new NotFoundException("reservation not found");
        if (!ResourceReservation.PENDING.equals(r.status)) {
            throw new BadRequestException("reservation is not pending");
        }
        ResourcePort port = ResourcePort.findById(r.portId);
        if (port == null || !port.isCapacityLimited()) {
            throw new BadRequestException("port is no longer reservable");
        }
        expireDueForPort(r.portId, now);
        List<ResourceReservation> active = liveReservations(r.portId, now);
        if (active.size() >= port.maxConcurrentUsers) throw new AtCapacityException(holdersOf(active));

        r.activate(now);
        r.decidedBy = actor;
        r.decidedAt = now;
        return r;
    }

    @Transactional
    public ResourceReservation reject(String reservationId, String actor) {
        ResourceReservation r = ResourceReservation.findById(reservationId);
        if (r == null) throw new NotFoundException("reservation not found");
        if (!ResourceReservation.PENDING.equals(r.status)) {
            throw new BadRequestException("reservation is not pending");
        }
        r.status = ResourceReservation.REJECTED;
        r.decidedBy = actor;
        r.decidedAt = Instant.now();
        return r;
    }

    /** Active rows on one port whose window still covers {@code now}. */
    public List<ResourceReservation> liveReservations(String portId, Instant now) {
        List<ResourceReservation> out = new ArrayList<>();
        for (ResourceReservation r : ResourceReservation.activeForPort(portId)) {
            if (r.isLiveAt(now)) out.add(r);
        }
        return out;
    }

    public List<Holder> holdersOf(List<ResourceReservation> active) {
        List<Holder> out = new ArrayList<>();
        for (ResourceReservation r : active) {
            User u = User.findById(r.userId);
            out.add(new Holder(r.userId,
                    u == null ? r.userId : u.name,
                    u == null ? null : u.email,
                    r.endsAt));
        }
        out.sort(Comparator.comparing(Holder::until, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /**
     * Flips rows whose window has closed. Called opportunistically before any
     * capacity decision so a request never loses to a slot that has in fact
     * already ended, independently of when the expiry job last ticked.
     */
    @Transactional
    public int expireDueForPort(String portId, Instant now) {
        int n = 0;
        for (ResourceReservation r : ResourceReservation.activeForPort(portId)) {
            if (r.endsAt != null && !r.endsAt.isAfter(now)) {
                r.status = ResourceReservation.EXPIRED;
                n++;
            }
        }
        return n;
    }

    /** All active rows anywhere that have run out — the scheduled job's unit of work. */
    public List<ResourceReservation> dueForExpiry(Instant now) {
        return ResourceReservation.list("status = ?1 and endsAt is not null and endsAt <= ?2",
                ResourceReservation.ACTIVE, now);
    }
}
