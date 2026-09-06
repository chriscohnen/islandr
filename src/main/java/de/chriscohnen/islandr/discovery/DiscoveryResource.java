package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Device-discovery endpoints for a site (ADR-0014). Admin-only. The scan is bound
 * to the site's own declared CIDR — there is no free-text range input. A real scan
 * of a site that declares a WireGuard tunnel gateway needs that gateway connected;
 * a hub-local site (no gateway peer) is scanned directly, and mock mode needs no
 * route at all (see {@link #requireScanReachable}). Independent of the enforcement
 * path (ADR-0014 §7): it works even in the degraded "enforcement unavailable" mode.
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
    @ResponseStatus(202)
    public DiscoveryDto.ScanStarted startScan(@Context ContainerRequestContext ctx, @PathParam("siteId") String siteId,
                                              @QueryParam("force") boolean force) {
        AuthContext a = Auth.requireAdmin(ctx);
        Site site = requireSite(siteId);
        requireScanReachable(site, force);
        DiscoveryJobs.Job job;
        try {
            job = jobs.start(siteId, site.cidr, site.dnsServerIp);   // supersedes any scan still running for this site
        } catch (IllegalArgumentException e) {     // CIDR not IPv4-enumerable or over the cap
            throw conflict(e.getMessage());
        }
        audit.logEvent(a.principal(), "discovery.scan_started", "Site:" + site.name + " (" + siteId + ")",
                Map.of("cidr", site.cidr, "hosts", job.total(), "forced", force));
        // Return the DTO directly (not via Response.accepted(...)) so Quarkus's
        // build-time analysis registers ScanStarted for native serialization — a
        // Response-wrapped entity is opaque to that analysis, which left the native
        // image emitting an empty body (no jobId) and the client polling /scan/undefined.
        return new DiscoveryDto.ScanStarted(job.id);
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
            String vendor = OuiVendorLookup.vendorFor(h.mac()).orElse(null); // same package — no FQN needed here
            hosts.add(new DiscoveryDto.HostView(h.ip(), h.openPorts(), h.typeGuess(), h.hostname(), known, h.mac(), vendor));
        }
        return new DiscoveryDto.ScanStatus(
                job.state().name().toLowerCase(), job.total(), job.done(), job.found(), hosts, job.error());
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
        // Tracks dnsNames claimed earlier in this same batch — a per-row DB check
        // alone wouldn't see an uncommitted sibling created two iterations ago.
        java.util.Set<String> claimedDnsNames = new java.util.HashSet<>();
        for (DiscoveryDto.ImportHost h : body.hosts()) {
            String ip = h.ip().strip();
            // Idempotent on (site, ip): re-importing an already-registered host is a no-op.
            if (Resource.count("siteId = ?1 and ip = ?2", siteId, ip) > 0) {
                skipped++;
                continue;
            }
            Resource r = Resource.createNew(siteId, h.name().strip(), ip, null, h.type());
            // Best-effort — a collision (existing resource or another row already
            // claimed it in this batch) just leaves this one nameless rather than
            // failing the whole import; the admin can set it by hand afterwards.
            r.dnsName = claimDnsName(siteId, h.dnsName(), claimedDnsNames);
            r.mac = (h.mac() == null || h.mac().isBlank()) ? null : h.mac().strip().toLowerCase(java.util.Locale.ROOT);
            r.persist();
            // Optionally adopt the discovered open TCP ports as ResourcePorts, so the
            // admin doesn't re-enter them by hand. Protocol is a best-effort label
            // from the well-known port; everything is editable afterwards.
            if (h.ports() != null) {
                for (int port : h.ports()) {
                    if (port < 1 || port > 65535) continue;
                    ResourcePort.createNew(r.id, port, null, "tcp", protocolForPort(port),
                            null, null, true, false, "native").persist();
                }
            }
            imported++;
            createdIps.add(ip);
        }
        audit.logEvent(a.principal(), "discovery.import", "Site:" + site.name + " (" + siteId + ")",
                Map.of("imported", imported, "skipped", skipped, "ips", createdIps));
        return new DiscoveryDto.ImportResult(imported, skipped);
    }

    // -- helpers --------------------------------------------------------------

    /** Normalizes (lowercase, blank -> null) and claims a dnsName for one row of
     *  a bulk import — null if blank, already used by an existing resource in
     *  the site, or already claimed by an earlier row in this same batch. */
    private static String claimDnsName(String siteId, String dnsName, java.util.Set<String> claimedInBatch) {
        if (dnsName == null || dnsName.isBlank()) return null;
        String normalized = dnsName.strip().toLowerCase(java.util.Locale.ROOT);
        if (!claimedInBatch.add(normalized)) return null; // already used earlier in this batch
        if (Resource.count("siteId = ?1 and lower(dnsName) = ?2", siteId, normalized) > 0) return null;
        return normalized;
    }

    private Site requireSite(String siteId) {
        Site s = Site.findById(siteId);
        if (s == null) throw new NotFoundException("site not found: " + siteId);
        return s;
    }

    /**
     * A scan needs a route into the site's CIDR (ADR-0014 §3). This only bites for a
     * real scan of a site that declares a WireGuard tunnel gateway:
     * <ul>
     *   <li>Mock mode probes nothing, so no route is required — discovery stays
     *       testable in Docker/dev without WireGuard.</li>
     *   <li>A site with no gateway peer is treated as directly reachable from the
     *       hub (e.g. the hub's own LAN): the scan is allowed and best-effort — if
     *       there is no route it simply finds nothing (R-140).</li>
     *   <li>A site that <em>declares</em> a gateway peer must have a recent handshake:
     *       a promised tunnel that is down is failed fast, because the scan would
     *       otherwise silently return zero hosts.</li>
     * </ul>
     * {@code force=true} skips this check entirely — e.g. an admin pre-configuring a
     * site while the enforcement plane is degraded (Docker socket proxy not yet
     * wired up, ahead of a planned native-instance rollout) and wants to confirm
     * network reachability directly rather than trust the (currently meaningless)
     * handshake timestamp. The scan itself is unaffected by the override — a route
     * that genuinely isn't there still just finds nothing (R-140).
     */
    private void requireScanReachable(Site site, boolean force) {
        if (force) return;
        if (!jobs.isRealScan()) return;
        if (site.gatewayPeerId == null) return;
        Peer gw = Peer.findById(site.gatewayPeerId);
        Instant threshold = Instant.now().minus(GATEWAY_WINDOW);
        if (gw == null || gw.lastSeenAt == null || !gw.lastSeenAt.isAfter(threshold)) {
            throw conflict("the site's gateway peer is not connected (no recent handshake)");
        }
    }

    private WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(Response.Status.CONFLICT).entity(message).build());
    }

    /** Best-effort UI protocol label for a discovered open port; matches the probe set (ADR-0014 §1). */
    private static String protocolForPort(int port) {
        return switch (port) {
            case 22 -> "SSH";
            case 80, 8080, 8123 -> "HTTP";
            case 443, 8006, 8443 -> "HTTPS";
            case 445 -> "SMB";
            case 554 -> "RTSP";
            case 631 -> "IPP";
            case 3389 -> "RDP";
            case 5900 -> "VNC";
            case 9100 -> "RAW";
            default -> "CUSTOM";
        };
    }
}
