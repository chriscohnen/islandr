package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/v1/identity/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OidcProviderResource {

    @Inject OidcProviderService svc;
    @Inject AuditService audit;

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

        // Mutual exclusion: enabling this one may have auto-disabled others.
        // One audit row per affected sibling so the timeline is honest.
        for (String otherKey : result.deactivatedOthers()) {
            audit.logEvent(actor.principal(), "oidc_provider.disable", "OidcProvider:" + otherKey,
                    Map.of("providerKey", otherKey, "reason", "mutual_exclusion_with:" + key));
        }

        return OidcProviderDto.Response.from(result.provider());
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
