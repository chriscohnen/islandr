package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.user.User;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self-service and admin endpoints for exclusive-capacity reservations
 * (issue #72).
 *
 * <p>Every state change recomputes the ruleset: a reservation is only
 * meaningful because {@link de.chriscohnen.islandr.firewall.RuleBuilder}
 * consults it, so granting or releasing one without a recompute would leave
 * nftables describing an access state that no longer exists.
 */
@Path("/api/v1/reservations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReservationResource {

    @Inject ReservationService reservations;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    /** The caller's own reservations — the portal's list. */
    @GET
    @Path("/mine")
    public List<ReservationDto.Response> mine(@Context ContainerRequestContext ctx) {
        AuthContext a = Auth.require(ctx);
        if (a.userId() == null) return List.of();   // local ENV admin owns no reservations
        return toResponses(ResourceReservation.list("userId", a.userId()));
    }

    /** Admin overview, optionally narrowed to one status. */
    @GET
    public List<ReservationDto.Response> list(@Context ContainerRequestContext ctx,
                                              @QueryParam("status") String status) {
        Auth.requireAdmin(ctx);
        List<ResourceReservation> rows = (status == null || status.isBlank())
                ? ResourceReservation.listAll()
                : ResourceReservation.list("status", status);
        return toResponses(rows);
    }

    /** Who is holding a port right now — powers the portal's "in use by" line. */
    @GET
    @Path("/holders/{portId}")
    public List<ReservationDto.HolderResponse> holders(@Context ContainerRequestContext ctx,
                                                       @PathParam("portId") String portId) {
        AuthContext a = Auth.require(ctx);
        ResourcePort port = ResourcePort.findById(portId);
        if (port == null) throw new jakarta.ws.rs.NotFoundException("port not found");
        // Same gate as requesting: only someone eligible for the resource may
        // see who currently holds one of its ports.
        if (!a.isAdmin() && (a.userId() == null || !acl.hasAnyGrant(a.userId(), port.resourceId))) {
            throw new jakarta.ws.rs.ForbiddenException("no grant for this resource");
        }
        return reservations.holdersOf(reservations.liveReservations(portId, java.time.Instant.now()))
                .stream()
                .map(h -> new ReservationDto.HolderResponse(h.userId(), h.userName(), h.userEmail(), h.until()))
                .toList();
    }

    @Inject AclService acl;

    @POST
    public Response request(@Context ContainerRequestContext ctx,
                            @Valid ReservationDto.CreateRequest body) {
        AuthContext a = Auth.require(ctx);
        if (a.userId() == null) {
            throw new BadRequestException("the local admin account cannot hold reservations");
        }
        ResourceReservation r;
        try {
            r = reservations.request(a.userId(), body.portId(), body.minutes());
        } catch (ReservationService.AtCapacityException e) {
            // 409, not 403: the request is well-formed and the caller is
            // entitled to ask — the resource is simply taken right now.
            return Response.status(Response.Status.CONFLICT)
                    .entity(ReservationDto.AtCapacityResponse.of(e.holders))
                    .build();
        }
        audit.logEvent(a.principal(),
                ResourceReservation.ACTIVE.equals(r.status) ? "reservation.grant" : "reservation.request",
                "Reservation:" + a.principal() + "/" + auditTarget(r),
                Map.of("minutes", String.valueOf(r.requestedMinutes),
                       "autoApproved", String.valueOf(ResourceReservation.ACTIVE.equals(r.status))));
        if (ResourceReservation.ACTIVE.equals(r.status)) rulesets.recomputeFromHook();
        return Response.status(Response.Status.CREATED).entity(toResponse(r)).build();
    }

    /** Early release by the holder. Admins revoke through the same path. */
    @DELETE
    @Path("/{id}")
    public ReservationDto.Response release(@Context ContainerRequestContext ctx,
                                           @PathParam("id") String id) {
        AuthContext a = Auth.require(ctx);
        ResourceReservation existing = ResourceReservation.findById(id);
        boolean ownsIt = existing != null && a.userId() != null && a.userId().equals(existing.userId);
        ResourceReservation r = ownsIt
                ? reservations.cancelOwn(a.userId(), id)
                : adminRevoke(ctx, id, a);
        audit.logEvent(a.principal(), ownsIt ? "reservation.release" : "reservation.revoke",
                "Reservation:" + r.userId + "/" + auditTarget(r),
                Map.of());
        rulesets.recomputeFromHook();
        return toResponse(r);
    }

    private ResourceReservation adminRevoke(ContainerRequestContext ctx, String id, AuthContext a) {
        Auth.requireAdmin(ctx);
        return reservations.revoke(id, a.principal());
    }

    @POST
    @Path("/{id}/approve")
    public Response approve(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        ResourceReservation r;
        try {
            r = reservations.approve(id, a.principal());
        } catch (ReservationService.AtCapacityException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(ReservationDto.AtCapacityResponse.of(e.holders))
                    .build();
        }
        audit.logEvent(a.principal(), "reservation.approve",
                "Reservation:" + r.userId + "/" + auditTarget(r),
                Map.of("minutes", String.valueOf(r.requestedMinutes)));
        rulesets.recomputeFromHook();
        return Response.ok(toResponse(r)).build();
    }

    @POST
    @Path("/{id}/reject")
    public ReservationDto.Response reject(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        ResourceReservation r = reservations.reject(id, a.principal());
        audit.logEvent(a.principal(), "reservation.reject",
                "Reservation:" + r.userId + "/" + auditTarget(r),
                Map.of());
        // No recompute: a rejected request never conferred access.
        return toResponse(r);
    }

    /** "File Server:3389" — the audit trail should name the port that was held. */
    private String auditTarget(ResourceReservation r) {
        Resource res = Resource.findById(r.resourceId);
        ResourcePort port = ResourcePort.findById(r.portId);
        String resName = res == null ? r.resourceId : res.name;
        return port == null ? resName : resName + ":" + port.port;
    }

    private List<ReservationDto.Response> toResponses(List<ResourceReservation> rows) {
        List<ReservationDto.Response> out = new ArrayList<>(rows.size());
        for (ResourceReservation r : rows) out.add(toResponse(r));
        return out;
    }

    private ReservationDto.Response toResponse(ResourceReservation r) {
        Resource res = Resource.findById(r.resourceId);
        Site site = res == null ? null : Site.findById(res.siteId);
        ResourcePort port = ResourcePort.findById(r.portId);
        User u = User.findById(r.userId);
        return new ReservationDto.Response(
                r.id, r.portId,
                port == null ? 0 : port.port,
                port == null ? null : port.transport,
                port == null ? null : (port.label != null && !port.label.isBlank() ? port.label : port.protocol),
                r.resourceId,
                res == null ? null : res.name,
                site == null ? null : site.name,
                r.userId, u == null ? null : u.name,
                r.status, r.requestedMinutes, r.requestedAt, r.startsAt, r.endsAt,
                r.decidedBy, r.decidedAt);
    }
}
