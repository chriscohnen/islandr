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
 * capacity-limited resource right now.
 *
 * <p>Deliberately does not touch the grant model. A grant answers "may this
 * user request this resource at all"; this service answers "does this user
 * hold one of its slots". {@link RuleBuilder} consults both before emitting a
 * rule, which is what makes the reservation *mandatory* rather than an
 * alternative route to access.
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

    /** One current holder of a slot, for telling a rejected requester who to coordinate with. */
    public record Holder(String userId, String userName, Instant until) {}

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
    public ResourceReservation request(String userId, String resourceId, int minutes) {
        Instant now = Instant.now();
        Resource res = Resource.findById(resourceId);
        if (res == null) throw new NotFoundException("resource not found");

        if (!res.isCapacityLimited()) {
            // Nothing to reserve: an unlimited resource is reachable on the
            // strength of the grant alone, so handing out a reservation would
            // imply an exclusivity the resource does not actually have.
            throw new BadRequestException("resource is not reservable");
        }
        // Checked before anything computes or reports holders: an ineligible
        // caller must not be able to learn who is using a resource by probing
        // it. hasAnyGrant is the eligibility half only — deliberately NOT the
        // reservation-aware canReachNow, which would make requesting a slot
        // require already holding one.
        if (!acl.hasAnyGrant(userId, resourceId)) {
            throw new ForbiddenException("no grant for this resource");
        }
        if (!DURATION_CHOICES.contains(minutes)) {
            throw new BadRequestException("unsupported duration");
        }
        int effectiveMinutes = capToResourceCeiling(res, minutes);

        expireDueFor(resourceId, now);

        for (ResourceReservation existing : ResourceReservation.openFor(userId)) {
            if (existing.resourceId.equals(resourceId)) {
                throw new BadRequestException("you already hold or have requested this resource");
            }
        }

        List<ResourceReservation> active = liveReservations(resourceId, now);
        boolean hasRoom = active.size() < res.maxConcurrentUsers;
        if (!hasRoom) throw new AtCapacityException(holdersOf(active));

        ResourceReservation r = ResourceReservation.createPending(userId, resourceId, effectiveMinutes, now);
        if (res.autoApproveReservations) r.activate(now);
        r.persist();
        return r;
    }

    /**
     * The duration ceiling is applied by trimming, not by rejecting: the
     * picker offers a fixed ladder, and a resource capped at 4h should hand a
     * 24h request a 4h slot rather than an error the user cannot act on.
     */
    static int capToResourceCeiling(Resource res, int requestedMinutes) {
        if (res.maxReservationMinutes == null) return requestedMinutes;
        return Math.min(requestedMinutes, res.maxReservationMinutes);
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
        Resource res = Resource.findById(r.resourceId);
        if (res == null || !res.isCapacityLimited()) {
            throw new BadRequestException("resource is no longer reservable");
        }
        expireDueFor(r.resourceId, now);
        List<ResourceReservation> active = liveReservations(r.resourceId, now);
        if (active.size() >= res.maxConcurrentUsers) throw new AtCapacityException(holdersOf(active));

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

    /** Active rows whose window still covers {@code now}. */
    public List<ResourceReservation> liveReservations(String resourceId, Instant now) {
        List<ResourceReservation> out = new ArrayList<>();
        for (ResourceReservation r : ResourceReservation.activeFor(resourceId)) {
            if (r.isLiveAt(now)) out.add(r);
        }
        return out;
    }

    public List<Holder> holdersOf(List<ResourceReservation> active) {
        List<Holder> out = new ArrayList<>();
        for (ResourceReservation r : active) {
            User u = User.findById(r.userId);
            out.add(new Holder(r.userId, u == null ? r.userId : u.name, r.endsAt));
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
    public int expireDueFor(String resourceId, Instant now) {
        int n = 0;
        for (ResourceReservation r : ResourceReservation.activeFor(resourceId)) {
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
