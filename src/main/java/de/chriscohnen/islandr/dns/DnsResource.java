package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.peer.IpSubnet;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Backs the System → DNS status page — a standalone showcase for the
 * built-in resolver (ADR-0023), separate from the toggle itself (which stays
 * in Settings → Netzwerk, same pattern as Firewall's dry-run toggle living in
 * Settings while {@code /firewall} shows its live status).
 */
@Path("/api/v1/dns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DnsResource {

    @Inject SettingsService settingsSvc;
    @Inject DnsQueryHandler queryHandler;
    @Inject DnsResolverService resolverSvc;

    @ConfigProperty(name = "islandr.dns.port", defaultValue = "53")
    int port;

    public record StatusResponse(
            boolean enabled,
            // True only while the listener is actually bound — can be false
            // while enabled=true (e.g. missing CAP_NET_BIND_SERVICE on port 53).
            boolean running,
            String zone,
            java.util.List<String> upstreams,
            // Null when wgSubnet itself can't be parsed (shouldn't happen —
            // @ValidCidr-enforced on save — but conf generation elsewhere
            // already treats this as a "don't crash on it" case, so this does too).
            String bindAddress,
            int port,
            long resolvableCount,
            // The exact FQDNs, not just the count — an admin shouldn't have to
            // derive the site slug by hand to know what to test.
            java.util.List<String> resolvableNames
    ) {}

    @GET
    @Path("/status")
    public StatusResponse status(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        var s = settingsSvc.get();
        var cfg = queryHandler.currentConfig();
        String bindAddress = null;
        if (s.wgSubnet != null && !s.wgSubnet.isBlank()) {
            try {
                bindAddress = IpSubnet.parse(s.wgSubnet).networkAddress();
            } catch (RuntimeException ignored) {
                // unparseable wgSubnet — leave bindAddress null, same
                // fail-safe DnsResolverService itself applies
            }
        }
        java.util.List<String> names = queryHandler.resolvableNames();
        return new StatusResponse(
                s.dnsResolverEnabled, resolverSvc.isRunning(), cfg.zone(), cfg.upstreams(),
                bindAddress, port, names.size(), names);
    }

    public record LookupRequest(@NotBlank String name) {}

    /** {@code result}: "answer" | "nxdomain" | "not-managed". {@code fqdn} is
     *  the canonical name that actually matched — worth showing since the
     *  zone-append/bare-name shortcuts mean it can differ from what was typed.
     *  {@code upstream} is which configured upstream server actually answered
     *  a "not-managed" lookup — null for "answer"/"nxdomain" (those never
     *  leave the zone) and also null if every upstream timed out. */
    public record LookupResponse(String result, String ip, String fqdn, String upstream) {}

    @POST
    @Path("/lookup")
    public LookupResponse lookup(@Context ContainerRequestContext ctx, @Valid LookupRequest body) {
        Auth.requireAdmin(ctx);
        DnsQueryHandler.Resolution r = queryHandler.resolveForAdminPreview(body.name());
        if (r instanceof DnsQueryHandler.Resolution.Answer a) {
            return new LookupResponse("answer", a.ip(), a.fqdn(), null);
        }
        if (r instanceof DnsQueryHandler.Resolution.NxDomain) {
            return new LookupResponse("nxdomain", null, null, null);
        }
        // Outside the managed zone — actually ask the configured upstream(s)
        // instead of just reporting "would be forwarded" (a live, on-demand,
        // admin-triggered query; never part of the resolver's own hot path).
        DnsResolverService.UpstreamAnswer up = resolverSvc.queryUpstreamForPreview(body.name());
        if (up != null) {
            return new LookupResponse("not-managed", up.ip(), null, up.upstream());
        }
        return new LookupResponse("not-managed", null, null, null);
    }
}
