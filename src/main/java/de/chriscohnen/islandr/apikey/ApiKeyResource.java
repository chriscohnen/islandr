package de.chriscohnen.islandr.apikey;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Admin console CRUD for external-API keys (issue #15, ADR-0026) — itself
 *  session-authenticated, same as every other admin page. */
@Path("/api/v1/api-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiKeyResource {

    @Inject ApiKeyService svc;
    @Inject AuditService audit;

    @GET
    public List<ApiKeyDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return svc.listAll().stream().map(ApiKeyDto.Response::from).toList();
    }

    @POST
    public ApiKeyDto.CreateResponse create(@Context ContainerRequestContext ctx,
                                           @Valid ApiKeyDto.CreateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        ApiKeyService.CreateResult result = svc.create(body.label(), a.principal());
        audit.logCreate(a.principal(), "api_key.create", "ApiKey:" + result.apiKey().id,
                Map.of("label", result.apiKey().label));
        return new ApiKeyDto.CreateResponse(ApiKeyDto.Response.from(result.apiKey()), result.rawKey());
    }

    @DELETE
    @Path("/{id}")
    public void revoke(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        ApiKey k = svc.get(id);
        svc.revoke(id, a.principal());
        audit.logEvent(a.principal(), "api_key.revoke", "ApiKey:" + id, Map.of("label", k.label));
    }
}
