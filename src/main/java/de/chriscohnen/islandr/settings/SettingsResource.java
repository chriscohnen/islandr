package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.wg.WgAdapter;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v1/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {

    @Inject SettingsService settings;
    @Inject AuditService audit;
    @Inject WgAdapter wg;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @GET
    public SettingsDto.Response get(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return SettingsDto.Response.from(settings.get(), appVersion);
    }

    @PUT
    public SettingsDto.Response update(@Context ContainerRequestContext ctx,
                                       @Valid SettingsDto.UpdateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Map<String, Object> before = settingsSnapshot(settings.get());
        Settings after = settings.update(body, actor.principal());
        audit.logUpdate(actor.principal(), "settings.update", "Settings:singleton",
                before, settingsSnapshot(after));
        return SettingsDto.Response.from(after, appVersion);
    }

    @GET
    @Path("/wg-probe")
    public Response wgProbe(@Context ContainerRequestContext ctx,
                            @QueryParam("iface") String iface) {
        Auth.requireAdmin(ctx);
        String effectiveIface = (iface != null && !iface.isBlank()) ? iface : "wg0";
        WgAdapter.ServerInfo info = wg.probeServer(effectiveIface);
        if (info == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "wg interface not accessible",
                                   "iface", effectiveIface))
                    .build();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("iface", effectiveIface);
        result.put("publicKey", info.publicKey());
        result.put("listenPort", info.listenPort());
        result.put("peerCount", info.peerCount());
        result.put("ifStatus", info.ifStatus());
        result.put("mtu", info.mtu() > 0 ? info.mtu() : null);
        // Auto-save probed MTU so it survives page reloads without manual adoption.
        if (info.mtu() > 0) {
            saveProbedMtu(info.mtu());
        }
        return Response.ok(result).build();
    }

    @jakarta.transaction.Transactional
    void saveProbedMtu(int mtu) {
        Settings s = settings.get();
        if (s.wgMtu == null || s.wgMtu != mtu) {
            s.wgMtu = mtu;
        }
    }

    @POST
    @Path("/wg-set-mtu")
    public Response setIfMtu(@Context ContainerRequestContext ctx,
                             @QueryParam("iface") String iface) {
        AuthContext a = Auth.requireAdmin(ctx);
        Settings s = settings.get();
        if (s.wgMtu == null || s.wgMtu <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "no MTU configured in settings")).build();
        }
        String effectiveIface = (iface != null && !iface.isBlank()) ? iface : "wg0";
        try {
            wg.setIfMtu(effectiveIface, s.wgMtu);
            audit.logEvent(a.principal(), "settings.set_mtu", "Firewall:ruleset",
                    Map.of("iface", effectiveIface, "mtu", s.wgMtu));
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    private static Map<String, Object> settingsSnapshot(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wgSubnet", s.wgSubnet);
        m.put("wgServerPublicKey", s.wgServerPublicKey);
        m.put("wgServerEndpoint", s.wgServerEndpoint);
        m.put("wgClientAllowedIps", s.wgClientAllowedIps);
        m.put("wgClientDns", s.wgClientDns == null ? "" : s.wgClientDns);
        m.put("privateKeyRetention", s.privateKeyRetention);
        m.put("gravatarEnabled", s.gravatarEnabled);
        return m;
    }
}
