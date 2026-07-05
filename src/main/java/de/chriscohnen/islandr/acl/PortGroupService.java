package de.chriscohnen.islandr.acl;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class PortGroupService {

    public List<PortGroup> listAll() {
        return PortGroup.<PortGroup>listAll(Sort.by("name"));
    }

    public PortGroup get(String id) {
        PortGroup g = PortGroup.findById(id);
        if (g == null) throw new NotFoundException("port group not found: " + id);
        return g;
    }

    public List<PortGroupMember> membersFor(String groupId) {
        return PortGroupMember.<PortGroupMember>list(
                "portGroupId = ?1", Sort.by("port"), groupId);
    }

    /** Bulk fetch of members keyed by group id — list view uses this to avoid N+1. */
    public Map<String, List<PortGroupMember>> membersByGroup() {
        return PortGroupMember.<PortGroupMember>listAll(Sort.by("portGroupId").and("port"))
                .stream()
                .collect(Collectors.groupingBy(m -> m.portGroupId));
    }

    @Transactional
    public PortGroup create(PortGroupDto.UpsertRequest req) {
        if (PortGroup.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a port group named '" + req.name() + "' already exists")
                            .build());
        }
        PortGroup g = PortGroup.createNew(req.name(), req.description());
        g.persist();
        replaceMembers(g.id, req.members());
        return g;
    }

    @Transactional
    public PortGroup update(String id, PortGroupDto.UpsertRequest req) {
        PortGroup g = get(id);
        if (!g.name.equals(req.name()) && PortGroup.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a port group named '" + req.name() + "' already exists")
                            .build());
        }
        g.name = req.name();
        g.description = req.description();
        replaceMembers(g.id, req.members());
        return g;
    }

    @Transactional
    public void delete(String id) {
        PortGroup g = get(id);
        // ON DELETE CASCADE cleans port_group_members. Resources that previously
        // had ports applied from this group keep their copies — that's the
        // whole point of the snapshot semantics.
        g.delete();
    }

    /**
     * Copy the group's current members onto the resource as {@link ResourcePort}
     * rows. Ports already present on the resource (same port+transport) are
     * left untouched, so applying twice is idempotent and applying after a
     * manual port-add doesn't clobber the manual entry.
     */
    @Transactional
    public PortGroupDto.ApplyResponse applyToResource(String resourceId, String groupId) {
        Resource r = Resource.findById(resourceId);
        if (r == null) throw new NotFoundException("resource not found: " + resourceId);
        PortGroup g = get(groupId);
        List<PortGroupMember> members = membersFor(g.id);
        if (members.isEmpty()) return new PortGroupDto.ApplyResponse(0, 0);

        // Build the existing-tuples set in one query so we don't hit the DB
        // per member (a group with 5 members would otherwise issue 5 SELECTs).
        Set<String> existing = ResourcePort.<ResourcePort>list("resourceId", r.id).stream()
                .map(p -> p.port + "/" + p.portEnd + "/" + p.transport)
                .collect(Collectors.toCollection(HashSet::new));

        int added = 0, skipped = 0;
        for (PortGroupMember m : members) {
            String key = m.port + "/" + m.portEnd + "/" + m.transport;
            if (existing.contains(key)) {
                skipped++;
                continue;
            }
            ResourcePort.createNew(r.id, m.port, m.portEnd, m.transport, m.protocol, m.label, null, true, false, "native").persist();
            existing.add(key);
            added++;
        }
        return new PortGroupDto.ApplyResponse(added, skipped);
    }

    /** Internal: drop all existing members, insert the new set. Used by create/update. */
    private void replaceMembers(String groupId, List<ResourceDto.PortRequest> wanted) {
        PortGroupMember.delete("portGroupId", groupId);
        if (wanted == null || wanted.isEmpty()) return;
        // Catch duplicate (port, transport) in the request itself before the
        // unique index does — the index error message is opaque.
        Set<String> seen = new HashSet<>();
        for (ResourceDto.PortRequest p : wanted) {
            String key = p.port() + "/" + p.portEnd() + "/" + p.transport();
            if (!seen.add(key)) {
                throw new WebApplicationException(
                        Response.status(Response.Status.CONFLICT)
                                .entity("duplicate port in request: " + key).build());
            }
            PortGroupMember.createNew(groupId, p.port(), p.portEnd(), p.transport(),
                    p.protocol(), p.label()).persist();
        }
    }

    /** Convenience for the audit-log snapshot — turns members into something jsonable. */
    public List<Map<String, Object>> memberSnapshot(String groupId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PortGroupMember m : membersFor(groupId)) {
            Map<String, Object> row = new HashMap<>();
            row.put("port", m.port);
            if (m.portEnd != null) row.put("portEnd", m.portEnd);
            row.put("transport", m.transport);
            row.put("protocol", m.protocol);
            if (m.label != null) row.put("label", m.label);
            out.add(row);
        }
        return out;
    }
}
