package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.acme.AcmeException;
import de.chriscohnen.islandr.acme.AcmeService;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.dns.DnsResolverService;
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
    @Inject AcmeService acmeSvc;
    @Inject DnsResolverService dnsResolverSvc;

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
        // Starts/stops the DNS resolver listener (ADR-0023) to match the just-saved
        // dnsResolverEnabled flag — no CDI "settings changed" event bus exists yet,
        // this is the simplest hook that keeps the two in sync without polling.
        dnsResolverSvc.reconcile();
        return toResponse(after);
    }

    @PUT
    @Path("/tls")
    public SettingsDto.Response updateTls(@Context ContainerRequestContext ctx,
                                          @Valid SettingsDto.TlsRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        TlsService.PemBundle bundle = TlsService.splitPemBundle(body.pem());
        String keyPem = tlsSvc.resolveKeyPem(bundle);
        Settings after = tlsSvc.updateManagedCertificate(bundle.certPem(), keyPem, actor.principal());
        audit.logUpdate(actor.principal(), "settings.tls_update", "Settings:singleton",
                null, Map.of("tlsMode", after.tlsMode));
        return toResponse(after);
    }

    /** Generates a private key + CSR for the Origin Server Certificate tab (#42) so an
     *  admin can bring it to an external CA themselves, instead of pasting an
     *  already-issued key/cert pair. The CSR stays visible/pending until a matching
     *  certificate is uploaded (PUT above, cert-only paste), ACME is enabled instead,
     *  or the admin uploads their own key+cert pair — all three clear it. */
    @POST
    @Path("/tls/csr")
    public SettingsDto.Response generateTlsCsr(@Context ContainerRequestContext ctx,
                                               @Valid SettingsDto.CsrRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Settings after = tlsSvc.generateCsr(body.domain(), actor.principal());
        audit.logUpdate(actor.principal(), "settings.tls_csr_generate", "Settings:singleton",
                null, Map.of("domain", body.domain()));
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

    /** Switches to ACME mode and runs one issuance attempt synchronously (up to
     *  {@code islandr.acme.poll-timeout}, ~60s default) — the same "block on a
     *  slow admin action, surface the result" pattern as "Read from WireGuard".
     *  A failure is recorded on Settings (acmeLastError) by {@link AcmeService}
     *  itself and returned as a normal 200 with that field populated, not a 5xx —
     *  enabling ACME with a bad domain is a validation-style outcome, not a
     *  server error, and the mode/domain the admin chose stays saved either way
     *  so the scheduler retries automatically. */
    @PUT
    @Path("/acme")
    public SettingsDto.Response enableAcme(@Context ContainerRequestContext ctx,
                                           @Valid SettingsDto.AcmeRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        settings.enableAcme(body.domain(), body.challengeType(), body.dnsProvider(), body.dnsApiToken(), actor.principal());
        try {
            acmeSvc.issueCertificate();
        } catch (AcmeException e) {
            // already recorded on Settings.acmeLastError — surfaced via toResponse below
        }
        Settings after = settings.get();
        audit.logUpdate(actor.principal(), "settings.acme_enable", "Settings:singleton",
                null, Map.of("tlsMode", after.tlsMode, "acmeDomain", after.acmeDomain,
                        "acmeLastError", after.acmeLastError == null ? "" : after.acmeLastError));
        return toResponse(after);
    }

    /** Resumes a "manual" dns-01 challenge (ADR-0020) once the admin has added
     *  the TXT record {@code enableAcme} above asked for. Same "block and
     *  surface acmeLastError on failure, not a 5xx" pattern as enableAcme. */
    @POST
    @Path("/acme/dns-continue")
    @Consumes(MediaType.WILDCARD)  // no body — don't reject for missing Content-Type
    public SettingsDto.Response continueAcmeDns(@Context ContainerRequestContext ctx) {
        AuthContext actor = Auth.requireAdmin(ctx);
        try {
            acmeSvc.continueManualDnsChallenge();
        } catch (AcmeException e) {
            // already recorded on Settings.acmeLastError — surfaced via toResponse below
        }
        Settings after = settings.get();
        audit.logUpdate(actor.principal(), "settings.acme_dns_continue", "Settings:singleton",
                null, Map.of("tlsMode", after.tlsMode,
                        "acmeLastError", after.acmeLastError == null ? "" : after.acmeLastError));
        return toResponse(after);
    }

    /** Aborts an in-progress ACME onboarding attempt — always clears a stuck
     *  manual dns-01 pending challenge; only resets the TLS mode back to
     *  "none" if no certificate has actually been issued yet (see
     *  {@link AcmeService#cancel}), so cancelling never discards an
     *  already-working certificate. */
    @DELETE
    @Path("/acme")
    public SettingsDto.Response cancelAcme(@Context ContainerRequestContext ctx) {
        AuthContext actor = Auth.requireAdmin(ctx);
        acmeSvc.cancel(actor.principal());
        Settings after = settings.get();
        audit.logUpdate(actor.principal(), "settings.acme_cancel", "Settings:singleton",
                null, Map.of("tlsMode", after.tlsMode));
        return toResponse(after);
    }

    private SettingsDto.Response toResponse(Settings s) {
        // "acme" stores its issued cert in the same tlsCertPem/tlsKeyPem columns as
        // "managed" (ADR-0019) — same expiry/cert-info display applies to both.
        boolean hasCert = "managed".equals(s.tlsMode) || "acme".equals(s.tlsMode);
        Instant expiresAt = hasCert ? tlsSvc.certificateExpiresAt(s.tlsCertPem) : null;
        TlsService.CertInfo certInfo = hasCert ? tlsSvc.certificateInfo(s.tlsCertPem) : null;
        return SettingsDto.Response.from(s, appVersion, encSvc.isConfigured(), wgInterface, expiresAt, certInfo);
    }

    /** Recomputes the client {@code AllowedIPs} preview from unsaved form values,
     *  so the Settings UI can show the actual result live while an admin edits
     *  tunnel mode / Auto-Manual / supernet, instead of only after a save.
     *  Any parameter left null/blank falls back to the currently saved setting. */
    @GET
    @Path("/allowed-ips-preview")
    public SettingsDto.AllowedIpsPreview allowedIpsPreview(
            @Context ContainerRequestContext ctx,
            @QueryParam("tunnelMode") String tunnelMode,
            @QueryParam("allowedIpsMode") String allowedIpsMode,
            @QueryParam("wgClientAllowedIps") String wgClientAllowedIps,
            @QueryParam("splitSupernet") String splitSupernet) {
        Auth.requireAdmin(ctx);
        Settings s = settings.get();
        String effectiveTunnelMode = (tunnelMode == null || tunnelMode.isBlank()) ? s.tunnelMode : tunnelMode;
        String effectiveAllowedIpsMode = (allowedIpsMode == null || allowedIpsMode.isBlank()) ? s.allowedIpsMode : allowedIpsMode;
        String effectiveManualValue = (wgClientAllowedIps == null) ? s.wgClientAllowedIps : wgClientAllowedIps;

        java.util.List<String> siteCidrs = de.chriscohnen.islandr.acl.Site.enabledGatewayCidrs();
        String preview = de.chriscohnen.islandr.peer.AllowedIpsCalculator.compute(
                effectiveTunnelMode, effectiveAllowedIpsMode, effectiveManualValue,
                s.wgSubnet, s.wgSubnet6, splitSupernet, siteCidrs,
                s.effectiveClientDns(), true);

        int outsideCount = 0;
        if ("SPLIT".equals(effectiveTunnelMode) && "AUTO".equals(effectiveAllowedIpsMode)
                && splitSupernet != null && !splitSupernet.isBlank()) {
            de.chriscohnen.islandr.peer.IpSubnet supernet;
            try {
                supernet = de.chriscohnen.islandr.peer.IpSubnet.parse(splitSupernet.trim());
            } catch (IllegalArgumentException e) {
                supernet = null; // malformed — fail safe by counting every site as uncovered
            }
            for (String cidr : siteCidrs) {
                boolean covered = false;
                if (supernet != null) {
                    try {
                        covered = supernet.containsSubnet(de.chriscohnen.islandr.peer.IpSubnet.parse(cidr));
                    } catch (IllegalArgumentException ignored) {
                        // malformed site CIDR — not coverable, counts as outside
                    }
                }
                if (!covered) outsideCount++;
            }
        }

        return new SettingsDto.AllowedIpsPreview(preview, outsideCount);
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
