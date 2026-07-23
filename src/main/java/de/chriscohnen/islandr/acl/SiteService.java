package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SiteService {

    public List<Site> listAll() {
        return Site.<Site>listAll(Sort.by("name"));
    }

    public Site get(String id) {
        Site s = Site.findById(id);
        if (s == null) throw new NotFoundException("site not found: " + id);
        return s;
    }

    public SiteDto.Response toResponse(Site s, int resourceCount) {
        if (s.gatewayPeerId == null) {
            return SiteDto.Response.from(s, resourceCount, null, null);
        }
        Peer gw = Peer.findById(s.gatewayPeerId);
        if (gw == null) {
            return SiteDto.Response.from(s, resourceCount, null, null);
        }
        Instant threshold = Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);
        boolean online = gw.lastSeenAt != null && gw.lastSeenAt.isAfter(threshold);
        return SiteDto.Response.from(s, resourceCount, gw.name, online);
    }

    public Map<String, Long> resourceCountBySite() {
        // Avoid N+1 in the list view: one COUNT(*) GROUP BY siteId rather
        // than one Resource.count(...) per Site row.
        List<Object[]> rows = Resource.getEntityManager()
                .createQuery("select r.siteId, count(r) from Resource r group by r.siteId", Object[].class)
                .getResultList();
        java.util.HashMap<String, Long> out = new java.util.HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], (Long) r[1]);
        return out;
    }

    @Transactional
    public Site create(SiteDto.UpsertRequest req) {
        if (Site.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a site named '" + req.name() + "' already exists")
                            .build());
        }
        Site s = Site.createNew(req.name(), req.cidr(), req.description());
        s.gatewayPeerId = validatedGatewayPeerId(req.gatewayPeerId());
        s.persist();
        return s;
    }

    @Transactional
    public Site update(String id, SiteDto.UpsertRequest req) {
        Site s = get(id);
        if (!s.name.equals(req.name()) && Site.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a site named '" + req.name() + "' already exists")
                            .build());
        }
        s.name = req.name();
        s.cidr = req.cidr();
        s.description = req.description();
        s.gatewayPeerId = validatedGatewayPeerId(req.gatewayPeerId());
        s.updatedAt = Instant.now();
        return s;
    }

    private String validatedGatewayPeerId(String peerId) {
        if (peerId == null || peerId.isBlank()) return null;
        if (Peer.findById(peerId) == null) throw new jakarta.ws.rs.NotFoundException("gateway peer not found: " + peerId);
        return peerId;
    }

    @Transactional
    public void delete(String id) {
        Site s = get(id);
        long resources = Resource.count("siteId", id);
        if (resources > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("site has " + resources + " resource(s); remove them first")
                            .build());
        }
        s.delete();
    }
}
