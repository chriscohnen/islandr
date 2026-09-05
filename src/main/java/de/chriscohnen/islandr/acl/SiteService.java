package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.peer.Peer;
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
public class SiteService {

    @Inject de.chriscohnen.islandr.settings.SettingsService settingsSvc;

    public List<Site> listAll() {
        return Site.<Site>listAll(Sort.by("name"));
    }

    public Site get(String id) {
        Site s = Site.findById(id);
        if (s == null) throw new NotFoundException("site not found: " + id);
        return s;
    }

    public SiteDto.Response toResponse(Site s, int resourceCount) {
        Boolean outside = outsideSplitSupernet(s);
        if (s.gatewayPeerId == null) {
            return SiteDto.Response.from(s, resourceCount, null, null, outside);
        }
        Peer gw = Peer.findById(s.gatewayPeerId);
        if (gw == null) {
            return SiteDto.Response.from(s, resourceCount, null, null, outside);
        }
        Instant threshold = Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);
        boolean online = gw.lastSeenAt != null && gw.lastSeenAt.isAfter(threshold);
        return SiteDto.Response.from(s, resourceCount, gw.name, online, outside);
    }

    private Boolean outsideSplitSupernet(Site s) {
        if (s.gatewayPeerId == null) return null;
        de.chriscohnen.islandr.settings.Settings settings = settingsSvc.get();
        if (!"SPLIT".equals(settings.tunnelMode) || !"AUTO".equals(settings.allowedIpsMode)) return null;
        if (settings.splitSupernet == null || settings.splitSupernet.isBlank()) return null;
        try {
            de.chriscohnen.islandr.peer.IpSubnet supernet = de.chriscohnen.islandr.peer.IpSubnet.parse(settings.splitSupernet);
            de.chriscohnen.islandr.peer.IpSubnet site = de.chriscohnen.islandr.peer.IpSubnet.parse(s.cidr);
            return !supernet.containsSubnet(site);
        } catch (IllegalArgumentException e) {
            return null; // malformed CIDR is caught elsewhere by @ValidCidr; don't fail the list here
        }
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
        String subdomain = normalizeSubdomain(req.subdomain());
        if (subdomain != null && Site.count("lower(subdomain) = ?1", subdomain) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a site with subdomain '" + subdomain + "' already exists")
                            .build());
        }
        Site s = Site.createNew(req.name(), req.cidr(), req.description());
        s.gatewayPeerId = validatedGatewayPeerId(req.gatewayPeerId());
        s.subdomain = subdomain;
        s.dnsServerIp = normalizeBlank(req.dnsServerIp());
        s.persist();
        return s;
    }

    /**
     * Every network a site-gateway peer routes that is not a Site yet.
     *
     * <p>A gateway's {@code siteAllowedCidrs} is already the authoritative list
     * of what sits behind it — the admin typed it once, when importing or
     * creating the peer. Asking them to type the same CIDRs again as Sites is
     * transcription work whose typos are silent: a Site whose CIDR does not
     * match what the gateway routes grants access to nothing.
     */
    public List<SiteDto.GatewayNetworkCandidate> gatewayImportPreview() {
        Map<String, String> siteNameByCidr = Site.<Site>listAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        s -> s.cidr.trim(), s -> s.name, (a, b) -> a));

        List<SiteDto.GatewayNetworkCandidate> out = new java.util.ArrayList<>();
        for (Peer p : Peer.<Peer>list("type", "site")) {
            if (p.siteAllowedCidrs == null || p.siteAllowedCidrs.isBlank()) continue;
            for (String raw : p.siteAllowedCidrs.split(",")) {
                String cidr = raw.trim();
                if (cidr.isEmpty()) continue;
                out.add(new SiteDto.GatewayNetworkCandidate(
                        p.id, p.name, cidr,
                        suggestSiteName(p.name, cidr),
                        siteNameByCidr.get(cidr)));
            }
        }
        return out;
    }

    /**
     * A name the admin will probably keep. Site names are unique, so a gateway
     * routing five networks cannot simply reuse the peer's name for all of them
     * — the network part of the CIDR disambiguates without inventing meaning
     * that isn't there.
     */
    private static String suggestSiteName(String peerName, String cidr) {
        String base = (peerName == null || peerName.isBlank()) ? "Site" : peerName.trim();
        String candidate = base + " " + cidr;
        if (Site.count("name", candidate) == 0) return candidate;
        for (int i = 2; i < 100; i++) {
            String numbered = candidate + " (" + i + ")";
            if (Site.count("name", numbered) == 0) return numbered;
        }
        return candidate;
    }

    /**
     * Create a Site per entry, wired to the gateway that routes it. An entry
     * whose CIDR is already a Site is skipped rather than rejected: the dialog
     * shows the whole gateway, and a partly-imported gateway must stay
     * re-runnable.
     */
    @Transactional
    public List<SiteDto.GatewayImportResult> gatewayImport(List<SiteDto.GatewayNetworkEntry> entries) {
        List<SiteDto.GatewayImportResult> results = new java.util.ArrayList<>();
        for (SiteDto.GatewayNetworkEntry e : entries) {
            String cidr = e.cidr().trim();
            if (Site.count("cidr", cidr) > 0) {
                results.add(new SiteDto.GatewayImportResult(cidr, "skipped", null));
                continue;
            }
            Peer gateway = Peer.findById(e.peerId());
            if (gateway == null || !gateway.isSite()) {
                throw new jakarta.ws.rs.BadRequestException(
                        "peerId " + e.peerId() + " is not a site peer");
            }
            Site s = create(new SiteDto.UpsertRequest(
                    e.name().trim(), cidr, e.description(), gateway.id, null, null));
            results.add(new SiteDto.GatewayImportResult(cidr, "imported", s.id));
        }
        return results;
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
        String subdomain = normalizeSubdomain(req.subdomain());
        if (subdomain != null && !subdomain.equals(s.subdomain)
                && Site.count("lower(subdomain) = ?1 and id <> ?2", subdomain, id) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a site with subdomain '" + subdomain + "' already exists")
                            .build());
        }
        s.name = req.name();
        s.cidr = req.cidr();
        s.description = req.description();
        s.gatewayPeerId = validatedGatewayPeerId(req.gatewayPeerId());
        s.subdomain = subdomain;
        s.dnsServerIp = normalizeBlank(req.dnsServerIp());
        s.updatedAt = Instant.now();
        return s;
    }

    /** Blank → null (derive live from name); otherwise lowercased as the
     *  case-insensitive uniqueness/lookup key (ADR-0023). */
    private static String normalizeSubdomain(String subdomain) {
        return (subdomain == null || subdomain.isBlank())
                ? null : subdomain.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Blank → null; otherwise the value as-is (unlike subdomain, this isn't
     *  a case-insensitive lookup key, so no lowercasing). */
    private static String normalizeBlank(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
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
