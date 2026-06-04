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
        if (ResourcePort.count(
                "resourceId = ?1 and port = ?2 and transport = ?3",
                r.id, req.port(), req.transport()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("port " + req.port() + "/" + req.transport()
                                    + " already exists on this resource").build());
        }
        ResourcePort p = ResourcePort.createNew(r.id, req.port(), req.transport(),
                req.protocol(), req.label());
        p.persist();
        return p;
    }

    @Transactional
    public ResourcePort updatePort(String portId, ResourceDto.PortRequest req) {
        ResourcePort p = ResourcePort.findById(portId);
        if (p == null) throw new NotFoundException("port not found: " + portId);
        if ((p.port != req.port() || !p.transport.equals(req.transport()))
                && ResourcePort.count(
                        "resourceId = ?1 and port = ?2 and transport = ?3 and id <> ?4",
                        p.resourceId, req.port(), req.transport(), portId) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("port " + req.port() + "/" + req.transport()
                                    + " already exists on this resource").build());
        }
        p.port = req.port();
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
