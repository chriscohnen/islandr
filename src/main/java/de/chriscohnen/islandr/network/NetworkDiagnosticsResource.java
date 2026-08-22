package de.chriscohnen.islandr.network;

import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Reports which diagnostic tools (ADR-0025) are actually available, so the UI can
 * gray out an action instead of offering one that will fail. The probe endpoints
 * themselves live on {@code ResourceResource} — a probe target is always a known
 * Resource (R-181), so those routes belong next to the entity they target, not here.
 */
@Path("/api/v1/diagnostics")
@Produces(MediaType.APPLICATION_JSON)
public class NetworkDiagnosticsResource {

    @Inject NetworkDiagnosticsAdapter diagnostics;

    @GET
    @Path("/availability")
    public NetworkDiagnosticsDto.AvailabilityView availability(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        NetworkDiagnosticsAdapter.Availability a = diagnostics.checkAvailability();
        return new NetworkDiagnosticsDto.AvailabilityView(a.ping(), a.tracepath(), a.mtr());
    }
}
