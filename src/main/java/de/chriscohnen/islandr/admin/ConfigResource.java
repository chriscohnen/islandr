package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

@Path("/api/v1/admin/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigResource {

    @Inject
    ConfigService configService;

    @GET
    @Path("/export")
    public ConfigExportDto.Export export(
            @Context ContainerRequestContext ctx,
            @QueryParam("includePrivateKeys") @DefaultValue("false") boolean includePrivateKeys) {
        Auth.requireAdmin(ctx);
        return configService.export(includePrivateKeys);
    }

    @POST
    @Path("/import")
    public ConfigExportDto.ImportResult importConfig(
            @Context ContainerRequestContext ctx,
            ConfigExportDto.Export payload) {
        Auth.requireAdmin(ctx);
        if (payload == null) throw new BadRequestException("request body required");
        return configService.importConfig(payload);
    }
}
