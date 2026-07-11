package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.peer.Peer;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Device-discovery endpoints for a site (ADR-0014). Admin-only. The scan is bound
 * to the site's own declared CIDR — there is no free-text range input — and needs
 * a connected gateway peer for a route. Independent of the enforcement path
 * (ADR-0014 §7): it works even in the degraded "enforcement unavailable" mode.
 */
@Path("/api/v1/sites/{siteId}/discovery")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DiscoveryResource {

    /** A gateway peer counts as connected if it handshook within this window. */
    private static final Duration GATEWAY_WINDOW = Duration.ofMinutes(3);

    @Inject DiscoveryJobs jobs;
    @Inject AuditService audit;

    @POST
    @Path("/scan")
    public Response startScan(@Context ContainerRequestContext ctx, @PathParam("siteId") String siteId) {
        AuthContext a = Auth.requireAdmin(ctx);
        Site site = requireSite(siteId);
        requireConnectedGateway(site);
        DiscoveryJobs.Job job;
        try {
            job = jobs.start(siteId, site.cidr);
        } catch (IllegalStateException e) {        // a scan is already running
            throw conflict(e.getMessage());
        } catch (IllegalArgumentException e) {     // CIDR not IPv4-enumerable or over the cap
            throw conflict(e.getMessage());
        }
        audit.logEvent(a.principal(), "discovery.scan_started", "Site:" + site.name + " (" + siteId + ")",
                Map.of("cidr", site.cidr, "hosts", job.total()));
        return Response.accepted(new DiscoveryDto.ScanStarted(job.id)).build();
    }

    @GET
    @Path("/scan/{jobId}")
    public DiscoveryDto.ScanStatus scanStatus(@Context ContainerRequestContext ctx,
                                              @PathParam("siteId") String siteId,
                                              @PathParam("jobId") String jobId) {
        Auth.requireAdmin(ctx);
        DiscoveryJobs.Job job = jobs.get(jobId);
        if (job == null || !job.siteId.equals(siteId)) throw new NotFoundException("scan not found: " + jobId);

        List<DiscoveryDto.HostView> hosts = new ArrayList<>();
        for (DiscoveryScanner.DiscoveredHost h : job.hosts()) {
            boolean known = Resource.count("siteId = ?1 and ip = ?2", siteId, h.ip()) > 0;
            hosts.add(new DiscoveryDto.HostView(h.ip(), h.openPorts(), h.typeGuess(), known));
        }
        return new DiscoveryDto.ScanStatus(
                job.state().name().toLowerCase(), job.total(), job.done(), hosts, job.error());
    }

    @DELETE
    @Path("/scan/{jobId}")
    public Response cancelScan(@Context ContainerRequestContext ctx,
                               @PathParam("siteId") String siteId,
                               @PathParam("jobId") String jobId) {
        Auth.requireAdmin(ctx);
        DiscoveryJobs.Job job = jobs.get(jobId);
        if (job == null || !job.siteId.equals(siteId)) throw new NotFoundException("scan not found: " + jobId);
        jobs.cancel(jobId);
        return Response.noContent().build();
    }

    @POST
    @Path("/import")
    @Transactional
    public DiscoveryDto.ImportResult importHosts(@Context ContainerRequestContext ctx,
                                                 @PathParam("siteId") String siteId,
                                                 @Valid DiscoveryDto.ImportRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Site site = requireSite(siteId);
        int imported = 0;
        int skipped = 0;
        List<String> createdIps = new ArrayList<>();
        for (DiscoveryDto.ImportHost h : body.hosts()) {
            String ip = h.ip().strip();
            // Idempotent on (site, ip): re-importing an already-registered host is a no-op.
            if (Resource.count("siteId = ?1 and ip = ?2", siteId, ip) > 0) {
                skipped++;
                continue;
            }
            Resource r = Resource.createNew(siteId, h.name().strip(), ip, null, h.type());
            r.persist();
            imported++;
            createdIps.add(ip);
        }
        audit.logEvent(a.principal(), "discovery.import", "Site:" + site.name + " (" + siteId + ")",
                Map.of("imported", imported, "skipped", skipped, "ips", createdIps));
        return new DiscoveryDto.ImportResult(imported, skipped);
    }

    // -- helpers --------------------------------------------------------------

    private Site requireSite(String siteId) {
        Site s = Site.findById(siteId);
        if (s == null) throw new NotFoundException("site not found: " + siteId);
        return s;
    }

    private void requireConnectedGateway(Site site) {
        if (site.gatewayPeerId == null) {
            throw conflict("this site has no gateway peer — the hub has no route into the subnet");
        }
        Peer gw = Peer.findById(site.gatewayPeerId);
        Instant threshold = Instant.now().minus(GATEWAY_WINDOW);
        if (gw == null || gw.lastSeenAt == null || !gw.lastSeenAt.isAfter(threshold)) {
            throw conflict("the site's gateway peer is not connected (no recent handshake)");
        }
    }

    private WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(Response.Status.CONFLICT).entity(message).build());
    }
}
