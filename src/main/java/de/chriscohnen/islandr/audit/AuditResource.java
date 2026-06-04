package de.chriscohnen.islandr.audit;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-side of the audit log. Admin-only. Cursor pagination via
 * {@code ?before=<iso-timestamp>}: the first page omits it, every following
 * page passes the {@code createdAt} of the oldest entry it received.
 *
 * <p>Optional {@code ?actor} / {@code ?action} narrow the result. Both match
 * exactly (no substring search) — for free-text search across {@code meta_json},
 * use the frontend's client-side filter.
 */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditResource {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    @Inject AuditService auditService;

    @GET
    public List<AuditDto.Response> list(@Context ContainerRequestContext ctx,
                                        @QueryParam("before") String beforeIso,
                                        @QueryParam("actor") String actor,
                                        @QueryParam("action") String action,
                                        @QueryParam("limit") Integer limit) {
        Auth.requireAdmin(ctx);

        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        // Build the query dynamically. Panache doesn't have a query builder
        // worth pulling in, so we assemble a small WHERE/params manually.
        List<String> where = new ArrayList<>();
        Parameters params = new Parameters();
        if (beforeIso != null && !beforeIso.isBlank()) {
            where.add("createdAt < :before");
            params.and("before", parseIso(beforeIso));
        }
        if (actor != null && !actor.isBlank()) {
            where.add("actor = :actor");
            params.and("actor", actor);
        }
        if (action != null && !action.isBlank()) {
            where.add("action = :action");
            params.and("action", action);
        }
        String hql = where.isEmpty() ? null : String.join(" and ", where);

        var query = (hql == null)
                ? AuditLog.<AuditLog>findAll(Sort.by("createdAt").descending().and("id"))
                : AuditLog.<AuditLog>find(hql, Sort.by("createdAt").descending().and("id"), params);
        return query.page(0, n).list().stream().map(AuditDto.Response::from).toList();
    }

    /**
     * Purge all entries older than {@code before}. The deletion itself is
     * written as a new audit row (audit.purge) so the log of who deleted what
     * is never silent. Returns the number of deleted rows.
     */
    @DELETE
    @Transactional
    public Response purge(@Context ContainerRequestContext ctx,
                          @QueryParam("before") String beforeIso) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (beforeIso == null || beforeIso.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException(
                    "'before' query param is required (ISO-8601, e.g. 2026-01-01T00:00:00Z)");
        }
        Instant cutoff = parseIso(beforeIso);
        long deleted = AuditLog.delete("createdAt < ?1", cutoff);
        auditService.logEvent(a.principal(), "audit.purge", "AuditLog",
                Map.of("before", beforeIso, "deletedCount", deleted));
        return Response.ok(Map.of("deleted", deleted, "before", beforeIso)).build();
    }

    private static Instant parseIso(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception ex) {
            throw new jakarta.ws.rs.BadRequestException(
                    "invalid 'before' timestamp; expected ISO-8601 (e.g. 2026-06-01T10:00:00Z)");
        }
    }
}
