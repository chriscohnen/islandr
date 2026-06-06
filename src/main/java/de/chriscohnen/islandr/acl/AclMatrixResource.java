package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The roles × resources grant matrix. The UI loads {@code GET /acl/matrix}
 * once to render the table, then batches every dirty cell into one
 * {@code PUT /acl/matrix}. We write one audit row per actually-changed cell
 * so future "who granted X to role Y" queries are answerable cell-by-cell.
 */
@Path("/api/v1/acl/matrix")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AclMatrixResource {

    @Inject RoleService roles;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;

    @GET
    public List<RoleDto.GrantCell> matrix(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return roles.matrix();
    }

    @PUT
    public Response apply(@Context ContainerRequestContext ctx,
                          @Valid RoleDto.MatrixApplyRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        List<RoleDto.GrantUpdate> updates = body == null || body.grants() == null
                ? List.of() : body.grants();
        List<RoleService.GrantDiff> diffs = roles.applyMatrix(updates);

        // Resolve names once for all diffs — avoids N×M lookups in the loop.
        Map<String, String> roleNames = resolveRoleNames(diffs);
        Map<String, String> resourceNames = resolveResourceNames(diffs);
        Map<String, String> portLabels = resolvePortLabels(diffs);

        for (RoleService.GrantDiff d : diffs) {
            // Action verb mirrors the change type so an admin filtering on
            // 'grant.create' / 'grant.update' / 'grant.delete' sees the right slice.
            String action = "grant." + d.change();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("role", roleNames.getOrDefault(d.roleId(), d.roleId()));
            details.put("resource", resourceNames.getOrDefault(d.resourceId(), d.resourceId()));
            if (d.toAllPorts() != null && d.toAllPorts()) details.put("ports", "all (current + future)");
            if (d.fromAllPorts() != null && d.fromAllPorts()) details.put("portsBefore", "all (current + future)");
            if (d.toPortIds() != null && !d.toPortIds().isEmpty())
                details.put("ports", portIdsTolabels(d.toPortIds(), portLabels));
            if (d.fromPortIds() != null && !d.fromPortIds().isEmpty())
                details.put("portsBefore", portIdsTolabels(d.fromPortIds(), portLabels));
            audit.logEvent(a.principal(), action,
                    "Grant:" + d.roleId() + "/" + d.resourceId(), details);
        }
        if (!diffs.isEmpty()) {
            // The matrix is the most rule-shifting action in the whole app —
            // ports / resources / roles change in one go. Recompute once
            // after the whole batch lands, not per cell.
            rulesets.recomputeFromHook();
        }
        return Response.ok(Map.of("changed", diffs.size())).build();
    }

    private static Map<String, String> resolveRoleNames(List<RoleService.GrantDiff> diffs) {
        List<String> ids = diffs.stream().map(RoleService.GrantDiff::roleId).distinct().toList();
        Map<String, String> out = new HashMap<>();
        for (Role r : Role.<Role>list("id in ?1", ids)) out.put(r.id, r.name);
        return out;
    }

    private static Map<String, String> resolveResourceNames(List<RoleService.GrantDiff> diffs) {
        List<String> ids = diffs.stream().map(RoleService.GrantDiff::resourceId).distinct().toList();
        Map<String, String> out = new HashMap<>();
        for (Resource r : Resource.<Resource>list("id in ?1", ids)) out.put(r.id, r.name);
        return out;
    }

    /** Collects every port UUID referenced across all diffs and resolves each
     *  to a human-readable "port/transport (protocol)" label. */
    private static Map<String, String> resolvePortLabels(List<RoleService.GrantDiff> diffs) {
        List<String> ids = diffs.stream()
                .flatMap(d -> Stream.of(
                        d.fromPortIds() == null ? List.<String>of() : d.fromPortIds(),
                        d.toPortIds()   == null ? List.<String>of() : d.toPortIds()))
                .flatMap(Collection::stream)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (ResourcePort p : ResourcePort.<ResourcePort>list("id in ?1", ids)) {
            String label = p.portSpec() + "/" + p.transport
                    + (p.protocol != null && !p.protocol.isBlank() ? " (" + p.protocol + ")" : "");
            out.put(p.id, label);
        }
        return out;
    }

    private static List<String> portIdsTolabels(List<String> portIds, Map<String, String> labels) {
        return portIds.stream()
                .map(id -> labels.getOrDefault(id, id))
                .collect(Collectors.toList());
    }
}
