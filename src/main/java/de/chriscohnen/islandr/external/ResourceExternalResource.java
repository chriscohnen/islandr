package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourceDto;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.ResourceService;
import de.chriscohnen.islandr.auth.Auth;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** External automation facade for resources (ADR-0027 precondition) —
 *  {@code /api/external/v1/resources}, read-only in v1. See
 *  {@link PeerExternalResource} for the separate-facade rationale. */
@Path("/api/external/v1/resources")
@Produces(MediaType.APPLICATION_JSON)
public class ResourceExternalResource {

    @Inject ResourceService resources;

    @GET
    public List<ResourceDto.Response> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Map<String, List<ResourcePort>> ports = resources.portsByResource();
        return resources.listAll().stream()
                .map((Resource r) -> ResourceDto.Response.from(r,
                        ports.getOrDefault(r.id, List.of()).stream()
                                .map(ResourceDto.PortResponse::from).toList()))
                .toList();
    }
}
