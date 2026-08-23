package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.SiteDto;
import de.chriscohnen.islandr.acl.SiteService;
import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** External automation facade for sites (ADR-0027 precondition) —
 *  {@code /api/external/v1/sites}, read-only in v1. See
 *  {@link PeerExternalResource} for the separate-facade rationale. */
@Path("/api/external/v1/sites")
@Produces(MediaType.APPLICATION_JSON)
public class SiteExternalResource {

    @Inject SiteService sites;

    @GET
    public List<SiteDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, Long> counts = sites.resourceCountBySite();
        return sites.listAll().stream()
                .map((Site s) -> sites.toResponse(s, counts.getOrDefault(s.id, 0L).intValue()))
                .toList();
    }
}
