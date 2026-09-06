package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.discovery.HostProbe;
import de.chriscohnen.islandr.discovery.OuiVendorLookup;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.network.NetworkDiagnosticsDto;
import de.chriscohnen.islandr.network.NetworkDiagnosticsService;
import de.chriscohnen.islandr.peer.Peer;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Direct routes on a {@link Resource}: get/update/delete + the port subresource.
 * Nested-under-site create/list lives in {@link SiteResourceResource}.
 */
@Path("/api/v1/resources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResourceResource {

    @Inject ResourceService resources;
    @Inject PortGroupService portGroups;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;
    @Inject NetworkDiagnosticsService diag;

    @ConfigProperty(name = "islandr.discovery.mode", defaultValue = "real")
    String discoveryMode;
    @ConfigProperty(name = "islandr.discovery.timeout", defaultValue = "1s")
    Duration hostTimeout;

    @GET
    public List<ResourceDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, List<ResourcePort>> ports = resources.portsByResource();
        return resources.listAll().stream()
                .map(r -> ResourceDto.Response.from(r,
                        ports.getOrDefault(r.id, List.of()).stream()
                                .map(ResourceDto.PortResponse::from).toList()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public ResourceDto.Response get(@Context ContainerRequestContext ctx,
                                    @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        Resource r = resources.get(id);
        return ResourceDto.Response.from(r,
                resources.portsFor(id).stream()
                        .map(ResourceDto.PortResponse::from).toList());
    }

    @PUT
    @Path("/{id}")
    public ResourceDto.Response update(@Context ContainerRequestContext ctx,
                                       @PathParam("id") String id,
                                       @Valid ResourceDto.UpsertRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource before = resources.get(id);
        Map<String, Object> beforeMap = Map.of(
                "name", before.name,
                "ip", before.ip,
                "description", before.description == null ? "" : before.description);
        Resource after = resources.update(id, body);
        audit.logUpdate(a.principal(), "resource.update", "Resource:" + after.name + " (" + id + ")",
                beforeMap,
                Map.of("name", after.name, "ip", after.ip,
                        "description", after.description == null ? "" : after.description));
        // IP changes flip the rule set — recompute. Name/description changes
        // only affect the comments, but recomputing is cheap and keeps the
        // hook contract uniform across update branches.
        rulesets.recomputeFromHook();
        return ResourceDto.Response.from(after,
                resources.portsFor(id).stream()
                        .map(ResourceDto.PortResponse::from).toList());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource before = resources.get(id);
        Map<String, Object> beforeMap = Map.of(
                "siteId", before.siteId, "name", before.name, "ip", before.ip);
        resources.delete(id);
        audit.logDelete(a.principal(), "resource.delete", "Resource:" + before.name + " (" + id + ")", beforeMap);
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }

    /**
     * Delete several resources in one call. Missing ids are skipped (idempotent),
     * each removal is audited individually, and the firewall ruleset is recomputed
     * once for the whole batch rather than once per resource.
     */
    @POST
    @Path("/bulk-delete")
    public ResourceDto.BulkDeleteResult bulkDelete(@Context ContainerRequestContext ctx,
                                                   ResourceDto.BulkDeleteRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        int deleted = 0;
        if (body != null && body.ids() != null) {
            for (String id : body.ids()) {
                Resource before = Resource.findById(id);
                if (before == null) continue;
                Map<String, Object> beforeMap = Map.of(
                        "siteId", before.siteId, "name", before.name, "ip", before.ip);
                resources.delete(id);
                audit.logDelete(a.principal(), "resource.delete",
                        "Resource:" + before.name + " (" + id + ")", beforeMap);
                deleted++;
            }
        }
        if (deleted > 0) rulesets.recomputeFromHook();
        return new ResourceDto.BulkDeleteResult(deleted);
    }

    @POST
    @Path("/{id}/ports")
    public ResourceDto.PortResponse addPort(@Context ContainerRequestContext ctx,
                                            @PathParam("id") String resourceId,
                                            @Valid ResourceDto.PortRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        ResourcePort p = resources.addPort(resourceId, body);
        audit.logCreate(a.principal(), "resource.port_add", "ResourcePort:" + p.id, Map.of(
                "resourceId", resourceId,
                "port", p.port,
                "transport", p.transport,
                "protocol", p.protocol,
                "label", p.label == null ? "" : p.label));
        rulesets.recomputeFromHook();
        return ResourceDto.PortResponse.from(p);
    }

    @PUT
    @Path("/{id}/ports/{portId}")
    public ResourceDto.PortResponse updatePort(@Context ContainerRequestContext ctx,
                                               @PathParam("id") String resourceId,
                                               @PathParam("portId") String portId,
                                               @Valid ResourceDto.PortRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        ResourcePort before = ResourcePort.findById(portId);
        Map<String, Object> beforeMap = before == null ? null : Map.of(
                "port", before.port, "transport", before.transport,
                "protocol", before.protocol,
                "label", before.label == null ? "" : before.label);
        ResourcePort after = resources.updatePort(portId, body);
        audit.logUpdate(a.principal(), "resource.port_update", "ResourcePort:" + portId,
                beforeMap,
                Map.of("port", after.port, "transport", after.transport,
                        "protocol", after.protocol,
                        "label", after.label == null ? "" : after.label));
        rulesets.recomputeFromHook();
        return ResourceDto.PortResponse.from(after);
    }

    @POST
    @Path("/{id}/ports/apply-group")
    public PortGroupDto.ApplyResponse applyGroup(@Context ContainerRequestContext ctx,
                                                 @PathParam("id") String resourceId,
                                                 @jakarta.validation.Valid PortGroupDto.ApplyRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        PortGroupDto.ApplyResponse result = portGroups.applyToResource(resourceId, body.portGroupId());
        // Audit only when something actually changed — applying the same
        // group twice is idempotent and shouldn't fill the log with no-ops.
        if (result.added() > 0) {
            de.chriscohnen.islandr.acl.Resource res = de.chriscohnen.islandr.acl.Resource.findById(resourceId);
            String resLabel = res != null ? res.name : resourceId;
            de.chriscohnen.islandr.acl.PortGroup pg = de.chriscohnen.islandr.acl.PortGroup.findById(body.portGroupId());
            String pgLabel = pg != null ? pg.name : body.portGroupId();
            audit.logEvent(a.principal(), "resource.port_group_apply",
                    "Resource:" + resLabel, Map.of(
                            "portGroup", pgLabel,
                            "added", result.added(),
                            "skippedExisting", result.skippedExisting()));
            // Only need a recompute when we actually added ports — applying
            // the same group twice is a no-op and shouldn't touch nftables.
            rulesets.recomputeFromHook();
        }
        return result;
    }

    @DELETE
    @Path("/{id}/ports/{portId}")
    public Response deletePort(@Context ContainerRequestContext ctx,
                               @PathParam("id") String resourceId,
                               @PathParam("portId") String portId) {
        AuthContext a = Auth.requireAdmin(ctx);
        ResourcePort before = ResourcePort.findById(portId);
        Map<String, Object> beforeMap = before == null ? null : Map.of(
                "resourceId", before.resourceId,
                "port", before.port, "transport", before.transport,
                "protocol", before.protocol);
        resources.deletePort(portId);
        audit.logDelete(a.principal(), "resource.port_delete", "ResourcePort:" + portId, beforeMap);
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }

    /**
     * On-demand re-identification of an already-registered resource (issue
     * #76): re-runs the exact same hostname-resolution chain and MAC/ARP
     * lookup discovery uses, targeted at this resource's current IP. Pure
     * read — nothing is persisted or audited here; the admin applies
     * whatever they want from the result via the ordinary save (PUT)
     * afterward. Respects islandr.discovery.mode (ADR-0014 §6) — mock mode
     * (dev/test default) never touches the network.
     */
    @POST
    @Path("/{id}/identify")
    public ResourceDto.IdentifyResponse identify(@Context ContainerRequestContext ctx,
                                                 @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        Resource r = resources.get(id);
        if (!"real".equalsIgnoreCase(discoveryMode)) {
            return new ResourceDto.IdentifyResponse(null, null, null);
        }
        Site site = Site.findById(r.siteId);
        String dnsServerIp = site != null ? site.dnsServerIp : null;
        HostProbe probe = new HostProbe(HostProbe.DEFAULT_TCP_PORTS, HostProbe.DEFAULT_UDP_PROBE_PORT,
                hostTimeout, dnsServerIp);
        HostProbe.ProbeResult result = probe.probe(r.ip);
        String vendor = OuiVendorLookup.vendorFor(result.mac()).orElse(null);
        return new ResourceDto.IdentifyResponse(result.hostname(), result.mac(), vendor);
    }

    // ── network diagnostics (ADR-0025) — admin-triggered ping/path-latency probe ──
    // Lives on this class, not a standalone resource: the probe target must always
    // be an existing Resource (R-181, never a free-text address), so it belongs next
    // to the entity it targets, same as the "ports" sub-resource above.

    @POST
    @Path("/{id}/diagnostics/ping")
    public NetworkDiagnosticsDto.PingResponse ping(@Context ContainerRequestContext ctx,
                                                    @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource resource = resources.get(id);
        List<NetworkDiagnosticsDto.PathHop> path = resolveDiagnosticsPath(resource);
        return diag.ping("resource:" + id, a.principal(), "Resource:" + resource.name + " (" + id + ")",
                id, resource.name, resource.ip, path);
    }

    @POST
    @Path("/{id}/diagnostics/tracepath")
    public NetworkDiagnosticsDto.TracepathResponse tracepath(@Context ContainerRequestContext ctx,
                                                              @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource resource = resources.get(id);
        List<NetworkDiagnosticsDto.PathHop> path = resolveDiagnosticsPath(resource);
        return diag.tracepath("resource:" + id, a.principal(), "Resource:" + resource.name + " (" + id + ")",
                id, resource.name, resource.ip, path);
    }

    @POST
    @Path("/{id}/diagnostics/mtr")
    public NetworkDiagnosticsDto.MtrResponse mtr(@Context ContainerRequestContext ctx,
                                                  @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        Resource resource = resources.get(id);
        List<NetworkDiagnosticsDto.PathHop> path = resolveDiagnosticsPath(resource);
        return diag.mtr("resource:" + id, a.principal(), "Resource:" + resource.name + " (" + id + ")",
                id, resource.name, resource.ip, path);
    }

    /**
     * hub → the resource's site gateway peer (if any) → the resource — the same chain
     * highlighted on Atlas (ADR-0025 §5). A hub-local site (no gateway peer) is just
     * hub → resource.
     */
    private List<NetworkDiagnosticsDto.PathHop> resolveDiagnosticsPath(Resource resource) {
        List<NetworkDiagnosticsDto.PathHop> path = new ArrayList<>();
        path.add(new NetworkDiagnosticsDto.PathHop("hub", null, "Hub", null));
        Site site = Site.findById(resource.siteId);
        if (site != null && site.gatewayPeerId != null) {
            Peer gw = Peer.findById(site.gatewayPeerId);
            if (gw != null) {
                path.add(new NetworkDiagnosticsDto.PathHop("site-gateway", gw.id, gw.name, site.name));
            }
        }
        path.add(new NetworkDiagnosticsDto.PathHop("resource", resource.id, resource.name, resource.ip));
        return path;
    }
}
