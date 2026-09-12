package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/v1/identity/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OidcProviderResource {

    @Inject OidcProviderService svc;
    @Inject AuditService audit;
    @Inject MicrosoftConfigCheck msCheck;

    @GET
    public List<OidcProviderDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return svc.listAll().stream().map(OidcProviderDto.Response::from).toList();
    }

    @GET
    @Path("/{key}")
    public OidcProviderDto.Response get(@Context ContainerRequestContext ctx,
                                        @PathParam("key") String key) {
        Auth.requireAdmin(ctx);
        return OidcProviderDto.Response.from(svc.get(key));
    }

    @PUT
    @Path("/{key}")
    public OidcProviderDto.Response update(@Context ContainerRequestContext ctx,
                                           @PathParam("key") String key,
                                           @Valid OidcProviderDto.UpdateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Map<String, Object> before = providerSnapshot(svc.get(key));
        OidcProviderService.UpdateResult result = svc.update(key, body, actor.principal());
        Map<String, Object> after = providerSnapshot(result.provider());

        // Pick a specific action when only the enabled flag flipped, so the
        // audit view can filter "every provider activation" without parsing
        // the JSON diff. Falls through to plain .update for any other change.
        Boolean enabledBefore = (Boolean) before.get("enabled");
        Boolean enabledAfter = (Boolean) after.get("enabled");
        boolean enabledFlipped = !java.util.Objects.equals(enabledBefore, enabledAfter);
        boolean onlyEnabledChanged = enabledFlipped && diffKeys(before, after).equals(java.util.Set.of("enabled"));
        if (onlyEnabledChanged) {
            String action = Boolean.TRUE.equals(enabledAfter)
                    ? "oidc_provider.enable" : "oidc_provider.disable";
            audit.logEvent(actor.principal(), action, "OidcProvider:" + key,
                    Map.of("providerKey", key));
        } else {
            audit.logUpdate(actor.principal(), "oidc_provider.update", "OidcProvider:" + key,
                    before, after);
        }

        // Mutual exclusion: enabling this one may have auto-disabled others —
        // MS365/Google or any custom provider (issue #69). One audit row per
        // affected sibling so the timeline is honest; the target label
        // distinguishes which kind of row it actually was.
        for (String otherKey : result.deactivatedOthers()) {
            boolean isFixed = OidcProvider.MICROSOFT.equals(otherKey) || OidcProvider.GOOGLE.equals(otherKey);
            String target = (isFixed ? "OidcProvider:" : "OidcCustomProvider:") + otherKey;
            audit.logEvent(actor.principal(), "oidc_provider.disable", target,
                    Map.of("providerKey", otherKey, "reason", "mutual_exclusion_with:" + key));
        }

        return OidcProviderDto.Response.from(result.provider());
    }

    /**
     * Checks the stored Entra values field by field and names the one at fault
     * (issue #81) — the alternative is waiting for the first wrong value to
     * surface as a login error that mentions neither the field nor the cause.
     *
     * <p>Admin-triggered only. It is the one place Islandr talks to Microsoft
     * outside a login, so it is audited like any other outbound action, and
     * the report carries no secret — only which field failed and why.
     */
    @POST
    @Path("/{key}/test")
    // The request carries no body, so the class-level @Consumes would answer
    // 415 to a client that sends no content-type — which is every client here.
    @Consumes(MediaType.WILDCARD)
    public MicrosoftConfigCheck.Report test(@Context ContainerRequestContext ctx,
                                            @PathParam("key") String key,
                                            @Context UriInfo uriInfo) {
        AuthContext actor = Auth.requireAdmin(ctx);
        OidcProvider p = svc.get(key);
        if (!p.isMicrosoft()) {
            throw new jakarta.ws.rs.BadRequestException(
                    "configuration test is Entra-specific and only available for the microsoft provider");
        }
        MicrosoftConfigCheck.Report report = msCheck.run(p, callbackUri(uriInfo, key));
        audit.logEvent(actor.principal(), "oidc_provider.test", "OidcProvider:" + key, Map.of(
                "providerKey", key,
                "result", report.ok() ? "ok" : "failed",
                "failingFields", report.checks().stream()
                        .filter(c -> MicrosoftConfigCheck.FAILED.equals(c.status()))
                        .map(MicrosoftConfigCheck.Check::field).toList()));
        return report;
    }

    /**
     * The redirect URI Islandr will actually send. Built the same way
     * {@code OidcAuthResource.absoluteCallbackUri} builds it — the check is
     * worthless if it probes a different string than the login does.
     */
    private static String callbackUri(UriInfo uriInfo, String key) {
        return uriInfo.getBaseUriBuilder()
                .path("api").path("v1").path("auth").path("oidc").path(key).path("callback")
                .build().toString();
    }

    private static Map<String, Object> providerSnapshot(OidcProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", p.enabled);
        m.put("clientId", p.clientId == null ? "" : p.clientId);
        // clientSecret is sensitive — AuditDiff redacts the value, but we put
        // a coarse "set / unset" indicator under a non-sensitive key so admins
        // can audit "did anyone rotate the secret" without seeing the secret.
        m.put("clientSecretSet", p.clientSecret != null && !p.clientSecret.isBlank());
        m.put("tenantId", p.tenantId == null ? "" : p.tenantId);
        m.put("allowedDomains", p.allowedDomains == null ? "" : p.allowedDomains);
        return m;
    }

    private static java.util.Set<String> diffKeys(Map<String, Object> before, Map<String, Object> after) {
        java.util.Set<String> changed = new java.util.HashSet<>();
        for (String k : before.keySet()) {
            if (!java.util.Objects.equals(before.get(k), after.get(k))) changed.add(k);
        }
        for (String k : after.keySet()) {
            if (!before.containsKey(k)) changed.add(k);
        }
        return changed;
    }
}
