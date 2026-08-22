package de.chriscohnen.islandr.webhook;

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

import java.util.List;
import java.util.Map;

/** Admin API for outgoing webhooks (issue #68). */
@Path("/api/v1/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookResource {

    @Inject WebhookService svc;
    @Inject WebhookDispatcher dispatcher;
    @Inject AuditService audit;

    @GET
    public List<WebhookDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return svc.listAll().stream().map(WebhookDto.Response::from).toList();
    }

    @GET
    @Path("/event-types")
    public List<String> eventTypes(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return WebhookEventType.ALL;
    }

    @GET
    @Path("/{id}")
    public WebhookDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        return WebhookDto.Response.from(svc.get(id));
    }

    @POST
    public WebhookDto.CreateResponse create(@Context ContainerRequestContext ctx,
                                            @Valid WebhookDto.CreateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        WebhookService.CreateResult result = svc.create(body, actor.principal());
        audit.logCreate(actor.principal(), "webhook.create", "Webhook:" + result.webhook().id,
                Map.of("url", result.webhook().url, "eventTypes", result.webhook().eventTypes));
        return new WebhookDto.CreateResponse(WebhookDto.Response.from(result.webhook()), result.plaintextSecret());
    }

    @PUT
    @Path("/{id}")
    public WebhookDto.Response update(@Context ContainerRequestContext ctx,
                                      @PathParam("id") String id,
                                      @Valid WebhookDto.UpdateRequest body) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Map<String, Object> before = snapshot(svc.get(id));
        Webhook w = svc.update(id, body, actor.principal());
        Map<String, Object> after = snapshot(w);
        audit.logUpdate(actor.principal(), "webhook.update", "Webhook:" + id, before, after);
        return WebhookDto.Response.from(w);
    }

    @POST
    @Path("/{id}/rotate-secret")
    public WebhookDto.SecretResponse rotateSecret(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext actor = Auth.requireAdmin(ctx);
        String secret = svc.rotateSecret(id, actor.principal());
        audit.logEvent(actor.principal(), "webhook.rotate_secret", "Webhook:" + id, Map.of("id", id));
        return new WebhookDto.SecretResponse(secret);
    }

    @POST
    @Path("/{id}/test")
    public WebhookDto.TestFireResponse test(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext actor = Auth.requireAdmin(ctx);
        svc.get(id); // 404s cleanly if the id is unknown, before we bother dispatching
        WebhookDto.TestFireResponse result = dispatcher.testFire(id);
        audit.logEvent(actor.principal(), "webhook.test", "Webhook:" + id,
                Map.of("success", result.success(), "status", result.status() == null ? "" : result.status()));
        return result;
    }

    @DELETE
    @Path("/{id}")
    public void delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext actor = Auth.requireAdmin(ctx);
        Webhook w = svc.get(id);
        svc.delete(id);
        audit.logEvent(actor.principal(), "webhook.delete", "Webhook:" + id, Map.of("url", w.url));
    }

    private static Map<String, Object> snapshot(Webhook w) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("url", w.url);
        m.put("description", w.description == null ? "" : w.description);
        m.put("eventTypes", w.eventTypes);
        m.put("enabled", w.enabled);
        return m;
    }
}
