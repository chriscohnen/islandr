package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EdgeHubService {

    @Inject SettingsService settings;

    public List<EdgeHub> listAll() {
        return EdgeHub.<EdgeHub>listAll(Sort.by("name"));
    }

    public EdgeHub get(String id) {
        EdgeHub e = EdgeHub.findById(id);
        if (e == null) throw new NotFoundException("edge hub not found: " + id);
        return e;
    }

    public Map<String, Long> siteCountByEdgeHub() {
        List<Object[]> rows = Site.getEntityManager()
                .createQuery("select s.edgeHubId, count(s) from Site s where s.edgeHubId is not null group by s.edgeHubId",
                        Object[].class)
                .getResultList();
        Map<String, Long> out = new java.util.HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], (Long) r[1]);
        return out;
    }

    public EdgeHubDto.Response toResponse(EdgeHub e, Map<String, Long> siteCounts) {
        return EdgeHubDto.Response.from(e, siteCounts.getOrDefault(e.id, 0L).intValue());
    }

    @Transactional
    public EdgeHub create(EdgeHubDto.UpsertRequest req) {
        // The feature switch is the gate, not just a UI nicety
        // (settings-edge-hubs-toggle): an edge hub created while the concept
        // is switched off would exist without appearing anywhere in the
        // console, and the switch could then never be turned off again
        // either, because it refuses while edge hubs exist.
        if (!settings.get().edgeHubsEnabled) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("edge hubs are switched off for this installation — turn them "
                                    + "on in Settings first.")
                            .build());
        }
        if (EdgeHub.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("an edge hub named '" + req.name() + "' already exists")
                            .build());
        }
        EdgeHub e = EdgeHub.createNew(req.name(), req.lat(), req.lng(), normalizeBlank(req.locationLabel()));
        e.enabled = req.enabled();
        e.endpoint = normalizeBlank(req.endpoint());
        e.serverPublicKey = normalizeBlank(req.serverPublicKey());
        e.dnsServerIp = normalizeBlank(req.dnsServerIp());
        e.persist();
        return e;
    }

    @Transactional
    public EdgeHub update(String id, EdgeHubDto.UpsertRequest req) {
        EdgeHub e = get(id);
        if (!e.name.equals(req.name()) && EdgeHub.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("an edge hub named '" + req.name() + "' already exists")
                            .build());
        }
        e.name = req.name();
        e.enabled = req.enabled();
        e.lat = req.lat();
        e.lng = req.lng();
        e.locationLabel = normalizeBlank(req.locationLabel());
        e.endpoint = normalizeBlank(req.endpoint());
        e.serverPublicKey = normalizeBlank(req.serverPublicKey());
        e.dnsServerIp = normalizeBlank(req.dnsServerIp());
        e.updatedAt = Instant.now();
        return e;
    }

    /**
     * Unassigns every {@link Site} pointed at this edge hub (falls them back
     * to the central hub) before removing the row — mirrors the DB-level
     * {@code ON DELETE SET NULL} (V86) so the in-memory state inside the
     * same transaction stays consistent with what the next read would see.
     */
    @Transactional
    public void delete(String id) {
        EdgeHub e = get(id);

        // Peers are refused, sites are re-homed, and the difference is the
        // point (ADR-I-0003). A site falling back to the central hub is
        // recoverable — its resources and grants survive, the hub simply
        // serves it again. A peer cannot fall back: its address belongs to
        // this edge's routing domain, and silently setting edge_hub_id to
        // null here would perform exactly the move the immutable assignment
        // forbids, by way of a different endpoint.
        long assigned = de.chriscohnen.islandr.peer.Peer.count("edgeHubId", id);
        if (assigned > 0) {
            throw new jakarta.ws.rs.WebApplicationException(
                    jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.CONFLICT)
                            .entity(assigned + " peer(s) still dial in at this edge hub. Delete them "
                                    + "first — a peer cannot be moved to another hub, so there is no "
                                    + "safe way to re-home them automatically.")
                            .build());
        }

        for (Site s : Site.<Site>list("edgeHubId", id)) {
            s.edgeHubId = null;
        }
        e.delete();
    }

    private static String normalizeBlank(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
