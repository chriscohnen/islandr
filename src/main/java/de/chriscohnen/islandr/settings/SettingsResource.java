package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.tls.TlsService;
import de.chriscohnen.islandr.wg.WgAdapter;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v1/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {

    @Inject SettingsService settings;
    @Inject AuditService audit;
    @Inject WgAdapter wg;
    @Inject EncryptionService encSvc;
    @Inject TlsService tlsSvc;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "islandr.wg.interface")
    String wgInterface;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @GET
    public SettingsDto.Response get(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return toResponse(settings.get());
    }

    @PUT
    public SettingsDto.Response update(@Context ContainerRequestContext ctx,
                                       @Valid SettingsDto.UpdateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Map<String, Object> before = settingsSnapshot(settings.get());
        Settings after = settings.update(body, actor.principal());
        audit.logUpdate(actor.principal(), "settings.update", "Settings:singleton",
                before, settingsSnapshot(after));
        return toResponse(after);
    }

    @PUT
    @Path("/tls")
    public SettingsDto.Response updateTls(@Context ContainerRequestContext ctx,
                                          @Valid SettingsDto.TlsRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        TlsService.PemBundle bundle = TlsService.splitPemBundle(body.pem());
        Settings after = tlsSvc.updateManagedCertificate(bundle.certPem(), bundle.keyPem(), actor.principal());
        audit.logUpdate(actor.principal(), "settings.tls_update", "Settings:singleton",
                null, Map.of("tlsMode", after.tlsMode));
        return toResponse(after);
    }

    @DELETE
    @Path("/tls")
    public SettingsDto.Response resetTls(@Context ContainerRequestContext ctx) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Settings after = tlsSvc.resetToDummy(actor.principal());
        audit.logUpdate(actor.principal(), "settings.tls_reset", "Settings:singleton",
                null, Map.of("tlsMode", after.tlsMode));
        return toResponse(after);
    }

    private SettingsDto.Response toResponse(Settings s) {
        boolean hasCert = "managed".equals(s.tlsMode);
        Instant expiresAt = hasCert ? tlsSvc.certificateExpiresAt(s.tlsCertPem) : null;
        TlsService.CertInfo certInfo = hasCert ? tlsSvc.certificateInfo(s.tlsCertPem) : null;
        return SettingsDto.Response.from(s, appVersion, encSvc.isConfigured(), wgInterface, expiresAt, certInfo);
    }

    @GET
    @Path("/wg-probe")
    public Response wgProbe(@Context ContainerRequestContext ctx,
                            @QueryParam("iface") String iface) {
        Auth.requireAdmin(ctx);
        String effectiveIface = (iface != null && !iface.isBlank()) ? iface : wgInterface;
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
        String effectiveIface = (iface != null && !iface.isBlank()) ? iface : wgInterface;
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

    @PUT
    @Path("/google-workspace")
    public SettingsDto.Response updateGoogleWorkspace(@Context ContainerRequestContext ctx,
                                                      SettingsDto.GoogleWorkspaceRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Settings after = settings.updateGoogleWorkspace(body == null
                ? new SettingsDto.GoogleWorkspaceRequest(null, null) : body, actor.principal());
        audit.logUpdate(actor.principal(), "settings.google_ws_update", "Settings:singleton",
                null, java.util.Map.of("googleWsConfigured", after.googleWsServiceAccountJson != null));
        return toResponse(after);
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
