package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * Admin endpoints for the firewall:
 * <ul>
 *   <li>{@code GET /api/v1/firewall} — current status (rule count, last
 *       apply timestamp, the authoritative ruleset text, stderr on
 *       failure).</li>
 *   <li>{@code POST /api/v1/firewall/resync} — force a recompute+apply
 *       even though no domain mutation happened. Used after a manual
 *       {@code nft delete table} or when the boot self-heal didn't run.</li>
 * </ul>
 */
@Path("/api/v1/firewall")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FirewallResource {

    @Inject RulesetService rulesets;
    @Inject SettingsService settings;

    @GET
    public FirewallDto.Response get(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return FirewallDto.Response.from(FirewallState.get(), settings.get().firewallDryRun);
    }

    @POST
    @Path("/resync")
    @Consumes(MediaType.WILDCARD)  // no body — don't reject for missing Content-Type
    public FirewallDto.Response resync(@Context ContainerRequestContext ctx) {
        AuthContext a = Auth.requireAdmin(ctx);
        FirewallState state = rulesets.recomputeAndApply(a.principal());
        return FirewallDto.Response.from(state, settings.get().firewallDryRun);
    }
}
