package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.AclResolutionService;
import de.chriscohnen.islandr.acl.AtlasDto;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who can reach what — the half of an access review that {@code /roles} could
 * only count. It answers from {@link AclResolutionService}, the same resolver
 * the ruleset, the portal and the browser-RDP gate resolve by: a second query
 * over the grant tables is exactly what R-171 cost, where a private copy had
 * stopped seeing the automatic "Everyone" role.
 *
 * <p>Names are resolved alongside the ids. A row saying
 * {@code 7f3a…→c1b9…} is not a review artefact, and an integration should not
 * have to fetch three more lists to make one readable.
 *
 * <p>One call returns the installation's complete access picture. That is a
 * different kind of payload from "list the peers", and the OpenAPI description
 * says so rather than leaving someone to notice.
 */
@Path("/api/external/v1/grants")
@Produces(MediaType.APPLICATION_JSON)
public class GrantExternalResource {

    @Inject AclResolutionService resolution;

    /**
     * @param subjectType {@code "user"} or {@code "site"} — the holder of the grant.
     * @param kind        how it is held: {@code role}, {@code type-grant},
     *                    {@code network-grant}, {@code user-direct} or
     *                    {@code site-direct}. A user can reach one resource
     *                    through several of these at once; each is its own row.
     * @param siteId      set only on a network grant, which names a network
     *                    rather than a single resource — {@code resourceId} is
     *                    then null, because the grant covers hosts Islandr may
     *                    never have been told about.
     * @param ports       the port labels a limited grant is scoped to; empty
     *                    when {@code allPorts} is true. A rendering, not data:
     *                    {@code "SSH 22"} names the UI's application label and
     *                    the number, and carries no transport.
     * @param portDetails the same ports as values — id, port, range end,
     *                    transport, application label. This is the field to
     *                    build a rule from; the labels above are for a human
     *                    reading the review. Before 1.0 only the labels
     *                    existed, which left port-limited grants unusable to a
     *                    consumer and {@code allPorts} grants the only
     *                    actionable ones.
     */
    public record Grant(
            String subjectType, String subjectId, String subjectName,
            String resourceId, String resourceName,
            String siteId, String siteName,
            String kind, String roleId, String roleName,
            boolean allPorts, List<String> ports,
            List<AtlasDto.PortDetail> portDetails) {}

    @GET
    public List<Grant> listAll(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        AtlasDto.Graph g = resolution.buildAtlasGraph();

        Map<String, String> userNames = g.users().stream()
                .collect(Collectors.toMap(AtlasDto.UserNode::id, AtlasDto.UserNode::name, (a, b) -> a));
        Map<String, AtlasDto.ResourceNode> resources = g.resources().stream()
                .collect(Collectors.toMap(AtlasDto.ResourceNode::id, Function.identity(), (a, b) -> a));
        Map<String, String> siteNames = g.sites().stream()
                .collect(Collectors.toMap(AtlasDto.SiteNode::id, AtlasDto.SiteNode::name, (a, b) -> a));

        return g.edges().stream().map(e -> {
            AtlasDto.ResourceNode res = e.resourceId() == null ? null : resources.get(e.resourceId());
            // A network grant carries its site directly; every other edge
            // inherits the site of the resource it points at.
            String siteId = e.siteId() != null ? e.siteId() : (res == null ? null : res.siteId());
            String subjectName = "site".equals(e.subjectType())
                    ? siteNames.get(e.subjectId())
                    : userNames.get(e.subjectId());
            return new Grant(
                    e.subjectType(), e.subjectId(), subjectName,
                    e.resourceId(), res == null ? null : res.name(),
                    siteId, siteId == null ? null : siteNames.get(siteId),
                    e.kind(), e.roleId(), e.roleName(),
                    e.allPorts(), e.portLabels(), e.portDetails());
        }).toList();
    }
}
