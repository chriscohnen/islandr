package de.chriscohnen.islandr.proxy;

import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;

/**
 * {@code GET /api/v1/enforcement/status} — whether the host enforcement plane is
 * reachable and applied (design §7). The Admin Console reads this to show the
 * "enforcement unavailable" banner when {@code status != active}, and the
 * per-change "saved, not yet enforced" hint. The {@code runtime} block carries
 * the container / socket-mode diagnostic for the Settings/Dashboard line.
 */
@Path("/api/v1/enforcement")
@Produces(MediaType.APPLICATION_JSON)
public class EnforcementResource {

    @Inject EnforcementStatus enforcement;
    @Inject ContainerDetector containerDetector;
    @Inject ProxyMode proxyMode;

    public record Runtime(boolean container, boolean socketMode) {}

    public record StatusResponse(
            String status,
            Instant lastReconcileAt,
            Instant lastProbeAt,
            String lastError,
            Runtime runtime) {}

    @GET
    @Path("/status")
    public StatusResponse status(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return new StatusResponse(
                enforcement.state().name().toLowerCase(),
                enforcement.lastReconcileAt(),
                enforcement.lastProbeAt(),
                enforcement.lastError(),
                new Runtime(containerDetector.inContainer(), proxyMode.isSocket()));
    }
}
