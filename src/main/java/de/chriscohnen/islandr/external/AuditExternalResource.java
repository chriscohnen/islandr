package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.audit.AuditDto;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

/**
 * The other half of an access review: who changed what, and when. Read-only —
 * the admin API's purge stays out of the facade on purpose, because an
 * integration that can delete the audit trail is not an audit trail.
 *
 * <p>Same reader as the admin console ({@link AuditService#query}), so a filter
 * cannot mean two different things depending on who asked.
 */
@Path("/api/external/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditExternalResource {

    @Inject AuditService auditService;

    /**
     * @param beforeIso cursor for the next page — pass the {@code createdAt} of
     *                  the oldest entry the previous page returned. Newest
     *                  first, so paging walks backwards in time.
     * @param limit     capped at {@link AuditService#MAX_LIMIT}; an export that
     *                  needs everything pages, rather than asking for it all at
     *                  once.
     */
    @GET
    public List<AuditDto.Response> list(@Context ContainerRequestContext ctx,
                                        @QueryParam("before") String beforeIso,
                                        @QueryParam("actor") String actor,
                                        @QueryParam("action") String action,
                                        @QueryParam("limit") Integer limit) {
        Auth.requireAdmin(ctx);
        Instant before = null;
        if (beforeIso != null && !beforeIso.isBlank()) {
            try {
                before = Instant.parse(beforeIso);
            } catch (Exception ex) {
                throw new BadRequestException(
                        "invalid 'before' timestamp; expected ISO-8601 (e.g. 2026-06-01T10:00:00Z)");
            }
        }
        return auditService.query(before, actor, action, limit)
                .stream().map(AuditDto.Response::from).toList();
    }
}
