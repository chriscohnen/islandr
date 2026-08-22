package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for generic OIDC providers (issue #69) — Okta/Auth0/Keycloak/any
 * issuer, alongside the two hardcoded ones managed by
 * {@link OidcProviderResource}. Kept as its own resource/table rather than
 * folded into that one, matching {@link OidcCustomProvider}'s own separation
 * rationale.
 */
@Path("/api/v1/identity/custom-providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OidcCustomProviderResource {

    @Inject OidcCustomProviderService svc;
    @Inject AuditService audit;

    @GET
    public List<OidcCustomProviderDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return svc.listAll().stream().map(OidcCustomProviderDto.Response::from).toList();
    }

    @GET
    @Path("/{id}")
    public OidcCustomProviderDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        return OidcCustomProviderDto.Response.from(svc.get(id));
    }

    @POST
    public OidcCustomProviderDto.Response create(@Context ContainerRequestContext ctx,
                                                  @Valid OidcCustomProviderDto.CreateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        OidcCustomProvider p = svc.create(body, actor.principal());
        audit.logCreate(actor.principal(), "oidc_custom_provider.create", "OidcCustomProvider:" + p.id,
                Map.of("displayName", p.displayName, "issuerUrl", p.issuerUrl, "preset",
                        p.preset == null ? "" : p.preset));
        return OidcCustomProviderDto.Response.from(p);
    }

    @PUT
    @Path("/{id}")
    public OidcCustomProviderDto.Response update(@Context ContainerRequestContext ctx,
                                                 @PathParam("id") String id,
                                                 @Valid OidcCustomProviderDto.UpdateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Map<String, Object> before = snapshot(svc.get(id));
        OidcCustomProviderService.UpdateResult result = svc.update(id, body, actor.principal());
        Map<String, Object> after = snapshot(result.provider());

        Boolean enabledBefore = (Boolean) before.get("enabled");
        Boolean enabledAfter = (Boolean) after.get("enabled");
        boolean onlyEnabledChanged = !java.util.Objects.equals(enabledBefore, enabledAfter)
                && diffKeys(before, after).equals(java.util.Set.of("enabled"));
        if (onlyEnabledChanged) {
            String action = Boolean.TRUE.equals(enabledAfter)
                    ? "oidc_custom_provider.enable" : "oidc_custom_provider.disable";
            audit.logEvent(actor.principal(), action, "OidcCustomProvider:" + id, Map.of("id", id));
        } else {
            audit.logUpdate(actor.principal(), "oidc_custom_provider.update", "OidcCustomProvider:" + id, before, after);
        }

        for (String otherKey : result.deactivatedOthers()) {
            boolean isFixed = OidcProvider.MICROSOFT.equals(otherKey) || OidcProvider.GOOGLE.equals(otherKey);
            String target = (isFixed ? "OidcProvider:" : "OidcCustomProvider:") + otherKey;
            audit.logEvent(actor.principal(), "oidc_provider.disable", target,
                    Map.of("providerKey", otherKey, "reason", "mutual_exclusion_with:" + id));
        }

        return OidcCustomProviderDto.Response.from(result.provider());
    }

    /** Re-runs discovery without changing enabled/credentials — "test connection". */
    @POST
    @Path("/{id}/rediscover")
    public OidcCustomProviderDto.Response rediscover(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext actor = Auth.requireAdmin(ctx);
        OidcCustomProvider p = svc.rediscover(id, actor.principal());
        audit.logEvent(actor.principal(), "oidc_custom_provider.rediscover", "OidcCustomProvider:" + id,
                Map.of("issuerUrl", p.issuerUrl));
        return OidcCustomProviderDto.Response.from(p);
    }

    @DELETE
    @Path("/{id}")
    public void delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext actor = Auth.requireAdmin(ctx);
        OidcCustomProvider p = svc.get(id);
        svc.delete(id);
        audit.logEvent(actor.principal(), "oidc_custom_provider.delete", "OidcCustomProvider:" + id,
                Map.of("displayName", p.displayName));
    }

    private static Map<String, Object> snapshot(OidcCustomProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", p.displayName);
        m.put("issuerUrl", p.issuerUrl);
        m.put("enabled", p.enabled);
        m.put("clientId", p.clientId == null ? "" : p.clientId);
        m.put("clientSecretSet", p.clientSecret != null && !p.clientSecret.isBlank());
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
