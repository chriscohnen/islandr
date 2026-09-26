package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
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
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port-range scan for a single resource. Admin-only, audited: a port scan is an
 * action against a network, even one the hub already trusts, and it is deliberately
 * not folded into device discovery ({@link DiscoveryResource}) — that endpoint
 * enumerates a whole CIDR at 15 fixed ports to answer "what is alive"; this one
 * takes an admin-typed range against one already-known IP to answer "what does it
 * offer" (see {@code loop/TASK.md}, task {@code port-scan-range}).
 */
@Path("/api/v1/resources/{resourceId}/port-scan")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PortScanResource {

    @Inject PortScanJobs jobs;
    @Inject AuditService audit;

    public record ScanRequest(String ports) {}

    @POST
    @ResponseStatus(202)
    public DiscoveryDto.PortScanStarted startScan(@Context ContainerRequestContext ctx,
                                                  @PathParam("resourceId") String resourceId,
                                                  ScanRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource resource = requireResource(resourceId);
        String spec = (body == null || body.ports() == null || body.ports().isBlank())
                ? PortRange.DEFAULT_SPEC : body.ports();
        PortScanJobs.Job job;
        try {
            job = jobs.start(resourceId, resource.ip, spec);   // supersedes any scan still running for this resource
        } catch (IllegalArgumentException e) {   // malformed port spec
            throw conflict(e.getMessage());
        }
        audit.logEvent(a.principal(), "port_scan.started", "Resource:" + resource.name + " (" + resourceId + ")",
                Map.of("ip", resource.ip, "ports", spec, "total", job.total()));
        return new DiscoveryDto.PortScanStarted(job.id);
    }

    @GET
    @Path("/{jobId}")
    public DiscoveryDto.PortScanStatus scanStatus(@Context ContainerRequestContext ctx,
                                                  @PathParam("resourceId") String resourceId,
                                                  @PathParam("jobId") String jobId) {
        Auth.requireAdmin(ctx);
        PortScanJobs.Job job = jobs.get(jobId);
        if (job == null || !job.resourceId.equals(resourceId)) {
            throw new NotFoundException("scan not found: " + jobId);
        }
        List<DiscoveryDto.OpenPortView> ports = new ArrayList<>();
        for (PortScanner.OpenPort p : job.openPorts()) {
            ports.add(new DiscoveryDto.OpenPortView(p.port(), p.service()));
        }
        return new DiscoveryDto.PortScanStatus(job.state().name(), job.total(), job.done(), job.found(),
                ports, job.error());
    }

    // DELETE, not POST: cancelling names no body, and a class-level @Consumes
    // would otherwise reject a bodyless POST with 415 before the handler runs
    // (matches DiscoveryResource#cancelScan's own choice for the same reason).
    @DELETE
    @Path("/{jobId}")
    public Response cancelScan(@Context ContainerRequestContext ctx,
                               @PathParam("resourceId") String resourceId,
                               @PathParam("jobId") String jobId) {
        AuthContext a = Auth.requireAdmin(ctx);
        PortScanJobs.Job job = jobs.get(jobId);
        if (job == null || !job.resourceId.equals(resourceId)) {
            throw new NotFoundException("scan not found: " + jobId);
        }
        jobs.cancel(jobId);
        audit.logEvent(a.principal(), "port_scan.cancelled", "Resource:" + resourceId,
                Map.of("jobId", jobId, "found", job.found()));
        return Response.noContent().build();
    }

    private Resource requireResource(String resourceId) {
        Resource r = Resource.findById(resourceId);
        if (r == null) throw new NotFoundException("resource not found: " + resourceId);
        return r;
    }

    private WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(Response.Status.CONFLICT).entity(message).build());
    }
}
