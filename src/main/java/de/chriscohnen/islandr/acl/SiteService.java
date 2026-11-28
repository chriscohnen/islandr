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
        String edgeHubName = s.edgeHubId == null ? null : edgeHubName(s.edgeHubId);
        if (s.gatewayPeerId == null) {
            return SiteDto.Response.from(s, resourceCount, null, null, outside, edgeHubName);
        }
        Peer gw = Peer.findById(s.gatewayPeerId);
        if (gw == null) {
            return SiteDto.Response.from(s, resourceCount, null, null, outside, edgeHubName);
        }
        Instant threshold = Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);
        boolean online = gw.lastSeenAt != null && gw.lastSeenAt.isAfter(threshold);
        return SiteDto.Response.from(s, resourceCount, gw.name, online, outside, edgeHubName);
    }

    private static String edgeHubName(String edgeHubId) {
        EdgeHub e = EdgeHub.findById(edgeHubId);
        return e == null ? null : e.name;
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
        s.edgeHubId = validatedEdgeHubId(req.edgeHubId());
        applyKind(s, req.kind());
        List<String> extras = validatedExtraCidrs(s, req.extraCidrs());
        s.persist();
        storeExtraCidrs(s.id, extras);
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
        // a range that is already a further range of a partner counts as known too
        for (SiteExtraCidr x : SiteExtraCidr.<SiteExtraCidr>listAll()) {
            Site owner = Site.findById(x.siteId);
            if (owner != null) siteNameByCidr.putIfAbsent(x.cidr.trim(), owner.name);
        }

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
            if (Site.count("cidr", cidr) > 0 || SiteExtraCidr.count("cidr", cidr) > 0) {
                results.add(new SiteDto.GatewayImportResult(cidr, "skipped", null));
                continue;
            }
            Peer gateway = Peer.findById(e.peerId());
            if (gateway == null || !gateway.isSite()) {
                throw new jakarta.ws.rs.BadRequestException(
                        "peerId " + e.peerId() + " is not a site peer");
            }
            Site s = create(new SiteDto.UpsertRequest(
                    e.name().trim(), cidr, e.description(), gateway.id, null, e.dnsServerIp(), null));
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
        s.edgeHubId = validatedEdgeHubId(req.edgeHubId());
        applyKind(s, req.kind());
        // null = the caller predates site-multi-cidr and says nothing about further ranges:
        // keep what is there (and still refuse a switch to "internal" that would orphan them).
        boolean keepExtras = req.extraCidrs() == null;
        List<String> extras = validatedExtraCidrs(s, keepExtras ? SiteExtraCidr.cidrsOf(s.id) : req.extraCidrs());
        s.updatedAt = Instant.now();
        if (!keepExtras) storeExtraCidrs(s.id, extras);
        return s;
    }

    /**
     * site-multi-cidr: the further address ranges of an external site, normalised and checked
     * against each other and the primary. A switch to "internal" with ranges left over is
     * refused rather than dropping them silently.
     */
    private static List<String> validatedExtraCidrs(Site s, List<String> raw) {
        List<String> extras = new java.util.ArrayList<>();
        if (raw != null) {
            for (String c : raw) {
                if (c != null && !c.isBlank()) extras.add(c.trim());
            }
        }
        if (extras.isEmpty()) return extras;
        if (!s.isExternal()) {
            throw new jakarta.ws.rs.BadRequestException(
                    "only an external network can have more than one address range");
        }
        if (extras.size() + 1 > Site.MAX_CIDRS) {
            throw new jakarta.ws.rs.BadRequestException(
                    "a network has at most " + Site.MAX_CIDRS + " address ranges");
        }
        List<String> all = new java.util.ArrayList<>();
        all.add(s.cidr.trim());
        all.addAll(extras);
        List<de.chriscohnen.islandr.peer.IpSubnet> parsed = new java.util.ArrayList<>();
        for (String c : all) {
            try {
                parsed.add(de.chriscohnen.islandr.peer.IpSubnet.parse(c));
            } catch (IllegalArgumentException e) {
                throw new jakarta.ws.rs.BadRequestException("not a CIDR: " + c);
            }
        }
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                if (all.get(i).equals(all.get(j)) || parsed.get(i).overlaps(parsed.get(j))) {
                    throw new jakarta.ws.rs.BadRequestException(
                            "address ranges overlap: " + all.get(i) + " and " + all.get(j));
                }
            }
        }
        return extras;
    }

    private static void storeExtraCidrs(String siteId, List<String> extras) {
        SiteExtraCidr.delete("siteId", siteId);
        int pos = 0;
        for (String c : extras) SiteExtraCidr.createNew(siteId, c, pos++).persist();
    }

    /**
     * egress-via-hub: an external site is reached through the central hub's
     * WAN interface. A gateway peer would mean the traffic goes into a tunnel
     * instead, and an edge hub would mean another hub's WAN — both contradict
     * the kind, so they are refused rather than silently ignored.
     */
    private static void applyKind(Site s, String kind) {
        s.kind = (kind == null || kind.isBlank()) ? Site.KIND_INTERNAL : kind;
        if (!s.isExternal()) return;
        if (s.gatewayPeerId != null) {
            throw new jakarta.ws.rs.BadRequestException(
                    "an external site is reached through the hub's own address and cannot have a gateway peer");
        }
        if (s.edgeHubId != null) {
            throw new jakarta.ws.rs.BadRequestException(
                    "an external site is reached through the central hub and cannot be served by an edge hub");
        }
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

    private String validatedEdgeHubId(String edgeHubId) {
        if (edgeHubId == null || edgeHubId.isBlank()) return null;
        if (EdgeHub.findById(edgeHubId) == null) throw new jakarta.ws.rs.NotFoundException("edge hub not found: " + edgeHubId);
        return edgeHubId;
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
        SiteExtraCidr.delete("siteId", id);
        s.delete();
    }
}
