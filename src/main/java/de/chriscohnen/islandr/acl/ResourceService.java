package de.chriscohnen.islandr.acl;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ResourceService {

    public List<Resource> listForSite(String siteId) {
        return Resource.<Resource>list("siteId = ?1", Sort.by("name"), siteId);
    }

    public List<Resource> listAll() {
        return Resource.<Resource>listAll(Sort.by("siteId").and("name"));
    }

    public Resource get(String id) {
        Resource r = Resource.findById(id);
        if (r == null) throw new NotFoundException("resource not found: " + id);
        return r;
    }

    public List<ResourcePort> portsFor(String resourceId) {
        return ResourcePort.<ResourcePort>list(
                "resourceId = ?1", Sort.by("port"), resourceId);
    }

    /**
     * Bulk fetch of every port keyed by its parent resource id — useful for
     * the matrix view, which renders ports inline under each resource header.
     */
    public Map<String, List<ResourcePort>> portsByResource() {
        return ResourcePort.<ResourcePort>listAll(Sort.by("resourceId").and("port"))
                .stream()
                .collect(Collectors.groupingBy(p -> p.resourceId));
    }

    @Transactional
    public Resource create(String siteId, ResourceDto.UpsertRequest req) {
        String ip = req.ip().strip();
        // Existence check on the parent site — catches typos / stale UI state
        // before we get an FK violation buried in a generic 500.
        if (Site.findById(siteId) == null) {
            throw new NotFoundException("site not found: " + siteId);
        }
        if (Resource.count("siteId = ?1 and ip = ?2", siteId, ip) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a resource at " + ip + " already exists in this site")
                            .build());
        }
        Resource r = Resource.createNew(siteId, req.name().strip(), ip, req.description(), req.type());
        r.persist();
        return r;
    }

    @Transactional
    public Resource update(String id, ResourceDto.UpsertRequest req) {
        String ip = req.ip().strip();
        Resource r = get(id);
        // IP can change but must remain unique within the site.
        if (!r.ip.equals(ip)
                && Resource.count("siteId = ?1 and ip = ?2 and id <> ?3", r.siteId, ip, id) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a resource at " + ip + " already exists in this site")
                            .build());
        }
        r.name = req.name().strip();
        r.ip = ip;
        r.description = req.description();
        if (req.type() != null && !req.type().isBlank()) {
            r.type = req.type();
        }
        return r;
    }

    @Transactional
    public void delete(String id) {
        Resource r = get(id);
        // ON DELETE CASCADE on resource_ports + grants cleans up the graph.
        r.delete();
    }

    @Transactional
    public ResourcePort addPort(String resourceId, ResourceDto.PortRequest req) {
        Resource r = get(resourceId);  // 404 if missing
        validatePortRange(req.port(), req.portEnd());
        if (portConflictExists(r.id, req.port(), req.portEnd(), req.transport(), null)) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("port " + portSpec(req.port(), req.portEnd()) + "/" + req.transport()
                                    + " already exists on this resource").build());
        }
        ResourcePort p = ResourcePort.createNew(r.id, req.port(), req.portEnd(), req.transport(),
                req.protocol(), req.label());
        p.persist();
        return p;
    }

    @Transactional
    public ResourcePort updatePort(String portId, ResourceDto.PortRequest req) {
        ResourcePort p = ResourcePort.findById(portId);
        if (p == null) throw new NotFoundException("port not found: " + portId);
        validatePortRange(req.port(), req.portEnd());
        boolean changed = p.port != req.port()
                || !java.util.Objects.equals(p.portEnd, req.portEnd())
                || !p.transport.equals(req.transport());
        if (changed && portConflictExists(p.resourceId, req.port(), req.portEnd(), req.transport(), portId)) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("port " + portSpec(req.port(), req.portEnd()) + "/" + req.transport()
                                    + " already exists on this resource").build());
        }
        p.port = req.port();
        p.portEnd = req.portEnd();
        p.transport = req.transport();
        p.protocol = req.protocol();
        p.label = req.label();
        return p;
    }

    @Transactional
    public void deletePort(String portId) {
        ResourcePort p = ResourcePort.findById(portId);
        if (p == null) throw new NotFoundException("port not found: " + portId);
        // ON DELETE CASCADE on role_resource_grant_ports — port-specific
        // grants pointing at this port go away with it. Grants with
        // all_ports=true survive (they were never tied to this port row).
        p.delete();
    }

    /**
     * HQL does not support `IS ?param` for nullable columns, so null vs. non-null
     * portEnd requires two separate query shapes.
     * @param excludePortId if non-null, excludes that row (used by updatePort)
     */
    private static boolean portConflictExists(String resourceId, int port, Integer portEnd,
                                              String transport, String excludePortId) {
        if (portEnd == null) {
            if (excludePortId == null) {
                return ResourcePort.count(
                        "resourceId = ?1 and port = ?2 and portEnd is null and transport = ?3",
                        resourceId, port, transport) > 0;
            } else {
                return ResourcePort.count(
                        "resourceId = ?1 and port = ?2 and portEnd is null and transport = ?3 and id <> ?4",
                        resourceId, port, transport, excludePortId) > 0;
            }
        } else {
            if (excludePortId == null) {
                return ResourcePort.count(
                        "resourceId = ?1 and port = ?2 and portEnd = ?3 and transport = ?4",
                        resourceId, port, portEnd, transport) > 0;
            } else {
                return ResourcePort.count(
                        "resourceId = ?1 and port = ?2 and portEnd = ?3 and transport = ?4 and id <> ?5",
                        resourceId, port, portEnd, transport, excludePortId) > 0;
            }
        }
    }

    /** Cross-field validation: port=0 must have portEnd=null; range end must exceed start. */
    private static void validatePortRange(int port, Integer portEnd) {
        if (port == 0 && portEnd != null) {
            throw new BadRequestException("portEnd must be null when port=0 (all-ports sentinel)");
        }
        if (portEnd != null) {
            if (portEnd <= port) {
                throw new BadRequestException("portEnd must be greater than port");
            }
            if (portEnd > 65535) {
                throw new BadRequestException("portEnd must be ≤ 65535");
            }
        }
    }

    private static String portSpec(int port, Integer portEnd) {
        if (port == 0) return "all";
        if (portEnd != null) return port + "-" + portEnd;
        return String.valueOf(port);
    }

    /** Convenience: validate that an admin's "limited" grant actually names ports of THIS resource. */
    static void validatePortsBelongToResource(String resourceId, List<String> portIds) {
        if (portIds == null || portIds.isEmpty()) return;
        long ok = ResourcePort.count("resourceId = ?1 and id in ?2", resourceId, portIds);
        if (ok != portIds.size()) {
            throw new BadRequestException(
                    "one or more port IDs do not belong to resource " + resourceId);
        }
    }
}
