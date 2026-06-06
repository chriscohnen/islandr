package de.chriscohnen.islandr.acl;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

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
        Site s = Site.createNew(req.name(), req.cidr(), req.description(), req.lat(), req.lng());
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
        s.lat = req.lat();
        s.lng = req.lng();
        return s;
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
