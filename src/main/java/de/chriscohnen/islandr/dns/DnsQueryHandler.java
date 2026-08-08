package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.AclService;
import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a queried DNS name against the managed zone (ADR-0023). A name
 * outside the zone is the caller's cue to forward it upstream instead —
 * this class only ever answers for, or refuses, names inside the zone.
 *
 * <p>No caching (v1, per the ADR) and a linear site scan per query — both
 * accepted deliberately for the small-team deployment scale this targets;
 * revisit if either becomes measurably slow in practice, not preemptively.
 */
@ApplicationScoped
public class DnsQueryHandler {

    static final String DEFAULT_ZONE = "islandr.internal";
    static final List<String> DEFAULT_UPSTREAMS = List.of("1.1.1.1", "8.8.8.8");

    @Inject SettingsService settingsSvc;
    @Inject AclService aclSvc;

    public sealed interface Resolution {
        record NotManaged() implements Resolution {}
        // fqdn is the canonical, fully-qualified name that actually matched —
        // not necessarily what was typed (resolveForAdminPreview's zone-append/
        // bare-name shortcuts mean those can differ; #resolve's real protocol
        // path always matches them exactly, so it's a no-op there).
        record Answer(String ip, String fqdn) implements Resolution {}
        record NxDomain() implements Resolution {}
    }

    private static final Resolution NOT_MANAGED = new Resolution.NotManaged();
    private static final Resolution NXDOMAIN = new Resolution.NxDomain();

    @Transactional
    public Resolution resolve(String queriedName, String sourceIp) {
        Settings s = settingsSvc.get();
        ZoneLookup lookup = lookupZone(s, queriedName);
        if (lookup.status() == ZoneStatus.NOT_MANAGED) return NOT_MANAGED;
        if (lookup.status() == ZoneStatus.NO_MATCH) return NXDOMAIN;

        Resource resource = lookup.resource();
        Peer peer = Peer.<Peer>find("assignedIp = ?1 or assignedIpv6 = ?1", sourceIp).firstResult();
        // No identity to check grants against (unknown source, or a site/gateway
        // peer with no owning user) — deny rather than guess. Conservative
        // default; ADR-0023 leaves the site-peer case as an open question.
        if (peer == null || peer.userId == null) return NXDOMAIN;

        return aclSvc.hasAnyGrant(peer.userId, resource.id)
                ? new Resolution.Answer(resource.ip, canonicalFqdn(resource, lookup.site(), normalizeZone(s.dnsResolverZone)))
                : NXDOMAIN;
    }

    /** Admin-only preview (the System → DNS page's mini lookup tool): the same
     *  zone matching as {@link #resolve}, but skips the ACL/peer-identity check
     *  entirely — an admin previewing the resolver isn't a connected peer and
     *  shouldn't need to fake being one to see what a name *would* resolve to.
     *  Never reachable except behind {@code Auth.requireAdmin} (DnsResource);
     *  the resolver's own socket path (DnsResolverService) always calls
     *  {@link #resolve}, never this.
     *
     *  <p>Also forgiving of partial input, unlike {@link #resolve} — an admin
     *  typing into a search box shouldn't have to already know the exact FQDN:
     *  {@code "printer.homeoffice"} gets the zone appended, and a bare
     *  {@code "printer"} resolves if exactly one resource anywhere has that
     *  DNS name. A real DNS client gets none of this — it either sends the
     *  FQDN or relies on its own OS-level search-domain config, standard DNS
     *  client behavior islandr has no protocol-level way to fake server-side,
     *  and this preview only feeds a search box, not the wire. */
    @Transactional
    public Resolution resolveForAdminPreview(String queriedName) {
        Settings s = settingsSvc.get();
        String zone = normalizeZone(s.dnsResolverZone);
        String candidate = normalizeName(queriedName);
        ZoneLookup lookup = lookupZone(s, candidate);

        if (lookup.status() == ZoneStatus.NOT_MANAGED) {
            // Only for something that already looks zone-relative — a bare
            // name ("printer") or exactly "<resource>.<site>" (one dot). A
            // candidate with two or more dots already looks like a complete,
            // independently-qualified name ("www.google.de") — appending the
            // zone to that produced a false NXDOMAIN before this guard (it
            // parsed as "www.google" + site "de", rejected for extra depth,
            // which is a false *inside-the-zone* answer for something that was
            // never meant to be zone-relative at all).
            long dots = candidate.chars().filter(c -> c == '.').count();
            if (dots <= 1 && !candidate.equals(zone) && !candidate.endsWith("." + zone)) {
                lookup = lookupZone(s, candidate + "." + zone);
            }
        }
        if (lookup.status() == ZoneStatus.NO_MATCH && !candidate.contains(".")) {
            List<Resource> matches = Resource.<Resource>list("lower(dnsName) = ?1", candidate);
            if (matches.size() == 1) {
                Resource r = matches.get(0);
                Site site = Site.findById(r.siteId);
                if (site != null) return new Resolution.Answer(r.ip, canonicalFqdn(r, site, zone));
            }
        }
        return switch (lookup.status()) {
            case NOT_MANAGED -> NOT_MANAGED;
            case NO_MATCH -> NXDOMAIN;
            case FOUND -> new Resolution.Answer(lookup.resource().ip, canonicalFqdn(lookup.resource(), lookup.site(), zone));
        };
    }

    private enum ZoneStatus { NOT_MANAGED, NO_MATCH, FOUND }
    private record ZoneLookup(ZoneStatus status, Site site, Resource resource) {}

    /** Canonical FQDN for a resource — {@code <dnsName>.<zone>} when it opted
     *  out of the subdomain layer ({@code dnsFlat}), otherwise
     *  {@code <dnsName>.<subdomain>.<zone>}. */
    private static String canonicalFqdn(Resource resource, Site site, String zone) {
        if (resource.dnsFlat) return resource.dnsName + "." + zone;
        return resource.dnsName + "." + effectiveSubdomain(site) + "." + zone;
    }

    /** A site's own DNS label: the explicit {@code subdomain} override when
     *  set (ADR-0023 follow-up — decouples it from the display name, so a
     *  cosmetic rename doesn't silently rename every resource's DNS name),
     *  otherwise the live-derived slug, unchanged from the original behavior. */
    private static String effectiveSubdomain(Site site) {
        return (site.subdomain != null && !site.subdomain.isBlank())
                ? site.subdomain.trim().toLowerCase(Locale.ROOT) : slugify(site.name);
    }

    /** Shared zone-matching core for {@link #resolve} and
     *  {@link #resolveForAdminPreview} — everything both methods need up to
     *  (but not including) the ACL decision, which is where they diverge. */
    private ZoneLookup lookupZone(Settings s, String queriedName) {
        if (!s.dnsResolverEnabled) return new ZoneLookup(ZoneStatus.NOT_MANAGED, null, null);

        String zone = normalizeZone(s.dnsResolverZone);
        String name = normalizeName(queriedName);
        if (!name.equals(zone) && !name.endsWith("." + zone)) return new ZoneLookup(ZoneStatus.NOT_MANAGED, null, null);
        if (name.equals(zone)) return new ZoneLookup(ZoneStatus.NO_MATCH, null, null); // bare zone apex

        String withoutZone = name.substring(0, name.length() - zone.length() - 1);
        int lastDot = withoutZone.lastIndexOf('.');
        if (lastDot < 0) {
            // A single label directly under the zone apex — only a resource
            // that opted out of the subdomain layer (dnsFlat) can match here.
            // Its dnsName is a global (not per-site) uniqueness domain, since
            // there's no site label left to disambiguate it — enforced at
            // save time (ResourceService), so at most one match is possible.
            Resource flat = Resource.<Resource>find("dnsFlat = true and lower(dnsName) = ?1", withoutZone).firstResult();
            if (flat == null) return new ZoneLookup(ZoneStatus.NO_MATCH, null, null);
            return new ZoneLookup(ZoneStatus.FOUND, Site.findById(flat.siteId), flat);
        }
        String resourceLabel = withoutZone.substring(0, lastDot);
        String siteLabel = withoutZone.substring(lastDot + 1);
        // Reject extra depth (<a>.<b>.<site>) — not a shape this resolver ever
        // issues; matching it to a single resource would be ambiguous.
        if (resourceLabel.contains(".")) return new ZoneLookup(ZoneStatus.NO_MATCH, null, null);

        Site site = findSiteBySlug(siteLabel);
        if (site == null) return new ZoneLookup(ZoneStatus.NO_MATCH, null, null);

        // dnsFlat = false: a flat resource only resolves via the single-label
        // path above, never additionally under its site's subdomain too — one
        // canonical FQDN per resource, not two aliases for the same one.
        Resource resource = Resource.<Resource>find(
                "siteId = ?1 and dnsFlat = false and lower(dnsName) = ?2", site.id, resourceLabel).firstResult();
        if (resource == null) return new ZoneLookup(ZoneStatus.NO_MATCH, site, null);

        return new ZoneLookup(ZoneStatus.FOUND, site, resource);
    }

    /** Everything {@link DnsResolverService} (and the System → DNS status page,
     *  via {@code DnsResource}) needs to know about the resolver's current
     *  configuration — one transactional read, since callers off the request
     *  thread (the resolver's own socket loops) must not touch entities
     *  directly. {@code zone} is always the *effective* zone (falls back to
     *  {@link #DEFAULT_ZONE} when unset), never null. */
    public record ResolverConfig(boolean enabled, String wgSubnet, String zone, List<String> upstreams) {}

    @Transactional
    public ResolverConfig currentConfig() {
        Settings s = settingsSvc.get();
        List<String> upstreams = new ArrayList<>();
        // dns_resolver_upstream is a deliberately separate field from
        // wgClientDns (what a *client* writes into its own DNS line, which can
        // legitimately hold split-DNS "~domain" tokens meaningless as a forward
        // target) — see Settings.java for the full reasoning.
        if (s.dnsResolverUpstream != null && !s.dnsResolverUpstream.isBlank()) {
            for (String part : s.dnsResolverUpstream.split(",")) {
                String v = part.strip();
                // Same InetAddress-based literal check IpAddressValidator already
                // uses elsewhere in this codebase. Accepted trade-off: a malformed
                // non-IP entry here costs one blocking hostname-lookup attempt per
                // query, same as any admin typo would; caching (deferred,
                // ADR-0023) fixes both at once if it ever matters in practice.
                if (v.isEmpty()) continue;
                try {
                    InetAddress.getByName(v);
                    upstreams.add(v);
                } catch (UnknownHostException ignored) {
                    // not a resolvable/parseable target — skip
                }
            }
        }
        if (upstreams.isEmpty()) upstreams = DEFAULT_UPSTREAMS;
        return new ResolverConfig(s.dnsResolverEnabled, s.wgSubnet, normalizeZone(s.dnsResolverZone), upstreams);
    }

    /** Count of resources with a DNS name set — the "N resolvable names"
     *  stat on the System → DNS page. Not scoped by zone/site since there's
     *  only ever one managed zone per install (ADR-0023). */
    @Transactional
    public long resolvableCount() {
        return Resource.count("dnsName is not null");
    }

    /** Full FQDN for every resource with a DNS name set — lets the System → DNS
     *  page show the admin the exact string to test instead of leaving them to
     *  derive the site slug by hand (the German-umlaut slugify fix above is the
     *  kind of mismatch this sidesteps entirely). Sorted for a stable display
     *  order; admin-facing only — never touches ACL, same as the rest of this
     *  page's status data. */
    @Transactional
    public List<String> resolvableNames() {
        String zone = normalizeZone(settingsSvc.get().dnsResolverZone);
        List<Resource> named = Resource.<Resource>list("dnsName is not null");
        List<String> fqdns = new ArrayList<>();
        for (Resource r : named) {
            if (r.dnsFlat) {
                fqdns.add(canonicalFqdn(r, null, zone));
                continue;
            }
            Site site = Site.findById(r.siteId);
            if (site == null) continue; // orphaned row, shouldn't happen — skip rather than throw
            fqdns.add(canonicalFqdn(r, site, zone));
        }
        fqdns.sort(String::compareTo);
        return fqdns;
    }

    private static String normalizeZone(String zone) {
        String z = (zone == null || zone.isBlank()) ? DEFAULT_ZONE : zone.trim().toLowerCase(Locale.ROOT);
        return stripTrailingDot(z);
    }

    private static String normalizeName(String name) {
        return stripTrailingDot(name.trim().toLowerCase(Locale.ROOT));
    }

    private static String stripTrailingDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static Site findSiteBySlug(String slug) {
        for (Site site : Site.<Site>listAll()) {
            if (effectiveSubdomain(site).equals(slug)) return site;
        }
        return null;
    }

    /** No dedicated slug column on {@link Site} — derived the same way every
     *  query, not stored, so a renamed site never goes stale. Collisions
     *  between two sites slugifying to the same string are unhandled (MVP).
     *
     *  <p>German umlauts/ß are transliterated (ü→ue, ö→oe, ä→ae, ß→ss) rather
     *  than dropped — "Büro Düsseldorf" slugifying to "b-ro-d-sseldorf" (every
     *  non-ASCII letter individually collapsed to a hyphen by the final regex)
     *  would be both ugly and lossy: distinct site names could collide once
     *  their umlauts vanish. Any other accented Latin letter (é, à, ñ, ...) is
     *  still just de-accented via NFD, not transliterated — good enough to
     *  avoid the same collision risk without hand-listing every language. */
    static String slugify(String s) {
        String lower = s.trim().toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        String deaccented = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = deaccented.replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "site" : slug;
    }
}
