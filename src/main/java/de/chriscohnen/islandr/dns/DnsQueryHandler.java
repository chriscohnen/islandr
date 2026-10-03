package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.AclService;
import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerSelfShare;
import de.chriscohnen.islandr.peer.IpSubnet;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    /** The label the hub answers for inside its own zone. Fixed, not
     *  configurable: a name that varies per installation is one more thing to
     *  look up before you can reach the console. */
    public static final String HUB_LABEL = "hub";
    static final List<String> DEFAULT_UPSTREAMS = List.of("1.1.1.1", "8.8.8.8");

    @Inject SettingsService settingsSvc;
    @Inject AclService aclSvc;

    public sealed interface Resolution {
        record NotManaged() implements Resolution {}
        // fqdn is the canonical, fully-qualified name that actually matched —
        // not necessarily what was typed (resolveForAdminPreview's zone-append/
        // bare-name shortcuts mean those can differ; #resolve's real protocol
        // path always matches them exactly, so it's a no-op there).
        record Answer(String ip, String fqdn, String resourceId) implements Resolution {}
        record NxDomain() implements Resolution {}
    }

    private static final Resolution NOT_MANAGED = new Resolution.NotManaged();
    private static final Resolution NXDOMAIN = new Resolution.NxDomain();

    @Transactional
    public Resolution resolve(String queriedName, String sourceIp) {
        Settings s = settingsSvc.get();

        // The hub's own name comes first and without an ACL check. The check
        // that guards resource names exists so a name never points at a host
        // nftables is about to drop packets for — it does not apply here: the
        // generated ruleset filters *forwarded* traffic, and every peer reaches
        // the hub itself regardless. Refusing the name would protect nothing,
        // and would break it for site gateways, which have no owning user and
        // so fail the resource path's identity check by construction.
        Resolution hub = resolveHub(s, queriedName);
        if (hub != null) return hub;

        // Resolved once, shared by the peer-name and resource-grant paths
        // below — both need "who is asking".
        Peer sourcePeer = Peer.<Peer>find("assignedIp = ?1 or assignedIpv6 = ?1", sourceIp).firstResult();

        // peer-dns-name-gated-by-self-share: a peer's own name resolves for
        // its owner and anyone with a live self-share on it (or
        // unconditionally, for an owner-less site/gateway peer, or when the
        // admin opted into dnsResolveAllResourcesAndPeers) — see
        // resolvePeerByName's own doc comment.
        Resolution peerAnswer = resolvePeerByName(s, queriedName, sourcePeer, false);
        if (peerAnswer != null) return peerAnswer;

        ZoneLookup lookup = lookupZone(s, queriedName);
        if (lookup.status() == ZoneStatus.NOT_MANAGED) return NOT_MANAGED;
        if (lookup.status() == ZoneStatus.NO_MATCH) return NXDOMAIN;

        Resource resource = lookup.resource();
        // No identity to check grants against (unknown source, or a site/gateway
        // peer with no owning user) — deny rather than guess. Conservative
        // default; ADR-0023 leaves the site-peer case as an open question.
        if (sourcePeer == null || sourcePeer.userId == null) return NXDOMAIN;

        // canReachNow, not hasAnyGrant: on a capacity-limited resource
        // (issue #72) a grant without a live reservation does not reach it,
        // and resolving its name would advertise a host nftables is about to
        // drop packets for. dns-resolve-all-resources-option waives this
        // check entirely when the admin opted in — same "a DNS answer grants
        // nothing by itself" reasoning resolveHub/resolvePeerByName already
        // apply unconditionally, just extended to every named resource.
        return (s.dnsResolveAllResourcesAndPeers || aclSvc.canReachNow(sourcePeer.userId, resource.id))
                ? new Resolution.Answer(resource.ip, canonicalFqdn(resource, lookup.site(), normalizeZone(s.dnsResolverZone)), resource.id)
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

        // Same hub record as the real path — an admin checking "hub.<zone>" in
        // the preview should see what a peer would get, not an NXDOMAIN that
        // only means the preview does not know about it.
        Resolution hub = resolveHub(s, candidate);
        if (hub != null) return hub;

        // bypassOwnershipCheck=true: same reasoning as the hub lookup just
        // above — the preview isn't a connected peer and shouldn't need to
        // fake being one (or a specific owner/self-share) to see whether a
        // name resolves at all.
        Resolution peerAnswer = resolvePeerByName(s, candidate, null, true);
        if (peerAnswer != null) return peerAnswer;

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
                if (site != null) return new Resolution.Answer(r.ip, canonicalFqdn(r, site, zone), r.id);
            }
        }
        return switch (lookup.status()) {
            case NOT_MANAGED -> NOT_MANAGED;
            case NO_MATCH -> NXDOMAIN;
            case FOUND -> new Resolution.Answer(lookup.resource().ip, canonicalFqdn(lookup.resource(), lookup.site(), zone), lookup.resource().id);
        };
    }

    /** Every user who would actually get an {@code Answer} for this resource
     *  through the real {@link #resolve} path — the admin lookup preview
     *  skips the ACL check entirely (see {@link #resolveForAdminPreview}'s
     *  own doc comment), so "resolves in the preview" alone doesn't tell an
     *  admin whether any *real* peer can actually reach it by name. Linear
     *  scan over every user, one {@code hasAnyGrant} check each — accepted
     *  at this codebase's target small-team scale (same trade-off as the
     *  zone lookup's own per-query site scan, see the class doc).
     *
     *  <p>Bug fixed 2026-10-03: with {@code Settings#dnsResolveAllResourcesAndPeers}
     *  on, {@link #resolve} answers for every user regardless of grants (see
     *  its own `||` there) — this must say the same thing, or the admin
     *  preview claims "no one can reach this, a real peer would get
     *  NXDOMAIN" for a name that in fact resolves for everyone. */
    /** Which of hub/peer/resource a given {@link Resolution.Answer} is — the
     *  admin lookup preview needs this to pick the right explanation (a
     *  resource's grants, a peer's ownership/self-share, or the hub's
     *  unconditional answer all need different wording; showing the
     *  resource-grants message for a peer name is what produced the
     *  2026-10-03 bug report). {@code resourceId} alone can't tell hub and
     *  peer apart — both leave it null — so this also re-checks
     *  {@link #hubLabels}. */
    @Transactional
    public ResolvableNameKind kindOfAnswer(Resolution.Answer a) {
        if (a.resourceId() != null) return ResolvableNameKind.RESOURCE;
        Settings s = settingsSvc.get();
        return hubLabels(s).contains(normalizeName(a.fqdn())) ? ResolvableNameKind.HUB : ResolvableNameKind.PEER;
    }

    @Transactional
    public List<String> grantedUserLabels(String resourceId) {
        boolean bypassesGrants = settingsSvc.get().dnsResolveAllResourcesAndPeers;
        List<String> labels = new ArrayList<>();
        for (User u : User.<User>listAll()) {
            if (bypassesGrants || aclSvc.hasAnyGrant(u.id, resourceId)) labels.add(u.name);
        }
        labels.sort(String::compareTo);
        return labels;
    }

    /**
     * The hub's own A record: the fixed {@code hub.<zone>} plus, when set, the
     * admin's own alias.
     *
     * <p>The alias is matched before the zone test on purpose — it is allowed
     * to sit outside the managed zone, because the name worth answering is the
     * one an admin already uses. Answering it inside the tunnel is what keeps
     * the console reachable by name when the upstream resolver is not, which is
     * exactly when someone is trying to open it.
     *
     * @return the answer, or {@code null} when this query is not about the hub
     *         and the normal resource path should handle it
     */
    private Resolution resolveHub(Settings s, String queriedName) {
        if (!s.dnsResolverEnabled) return null;
        String name = normalizeName(queriedName);
        String hubName = HUB_LABEL + "." + normalizeZone(s.dnsResolverZone);
        String alias = (s.dnsHubAlias == null || s.dnsHubAlias.isBlank())
                ? null : normalizeName(s.dnsHubAlias);
        boolean isHub = name.equals(hubName) || (alias != null && name.equals(alias));
        if (!isHub) return null;

        String ip = hubAddress(s);
        // No tunnel address to answer with (subnet unset or unparseable) is not
        // an NXDOMAIN for a name we own — it is us having nothing to say. Fall
        // through so the query takes its normal course instead of a lie.
        if (ip == null) return null;
        return new Resolution.Answer(ip, name, null);
    }

    /** The name(s) that currently resolve to the hub itself — the fixed
     *  {@code hub.<zone>} plus the admin's own alias, if set. Mirrors
     *  {@link #resolveHub}'s own guards exactly (resolver enabled, a real
     *  tunnel address to answer with), so this list — used by
     *  {@link #resolvableNames()} and {@link #resolvableCount()} for the
     *  System → DNS page — never claims a name resolves that the real
     *  {@link #resolve} path would actually refuse. */
    private List<String> hubLabels(Settings s) {
        if (!s.dnsResolverEnabled || hubAddress(s) == null) return List.of();
        List<String> labels = new ArrayList<>();
        labels.add(HUB_LABEL + "." + normalizeZone(s.dnsResolverZone));
        if (s.dnsHubAlias != null && !s.dnsHubAlias.isBlank()) {
            labels.add(normalizeName(s.dnsHubAlias));
        }
        return labels;
    }

    /** The hub's tunnel address, by the same network+1 convention the peer
     *  configs and the Atlas graph already use. */
    private static String hubAddress(Settings s) {
        if (s.wgSubnet == null || s.wgSubnet.isBlank()) return null;
        try {
            return IpSubnet.parse(s.wgSubnet).networkAddress();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * A peer's own name, as a single label directly under the zone apex
     * (peer-dns-name) — {@code <peer>.<zone>}, the same shape a flat resource
     * uses, but for the peer's own tunnel address rather than something it
     * offers.
     *
     * <p><b>Gated, since peer-dns-name-gated-by-self-share</b> (it answered
     * unconditionally for every peer before that — the class's own history
     * carried a long note about this being a known, deliberately-left-open
     * gap, back when {@code peer-self-share} itself hadn't shipped yet and
     * there was no concept of "this one named colleague" to check against):
     * an owner-less peer (a site/gateway, {@code peer.userId == null}) still
     * resolves for anyone, same reasoning as {@link #resolveHub} — it is
     * infrastructure, not a personal device, and access to whatever is
     * <em>behind</em> it is separately governed by the ACL regardless. A
     * peer with an owner resolves only for that owner's own other devices,
     * or a user holding a currently-live {@link PeerSelfShare} on it (any
     * port — the DNS answer only names an address, never a port), unless
     * {@code bypassOwnershipCheck} is set (the admin lookup preview) or
     * {@code Settings#dnsResolveAllResourcesAndPeers} is on (the default —
     * see that field's own doc comment for why opt-out, not opt-in).
     *
     * <p><b>Known gap, not solved here:</b> a peer's label and a flat
     * resource's {@code dnsName} share the same zone-apex, single-label
     * namespace but are validated independently of each other (peer names
     * against each other in {@link de.chriscohnen.islandr.peer.PeerService},
     * flat resource names against each other in {@code ResourceService}) — a
     * peer is checked first here, so it would silently shadow a same-named
     * flat resource rather than either being rejected at write time. Left
     * open rather than guessed at; revisit if it ever bites in practice.
     *
     * @param sourcePeer who is asking — null when unknown or when {@code
     *        bypassOwnershipCheck} makes it irrelevant (the admin preview,
     *        which isn't a connected peer and shouldn't need to fake being
     *        one)
     * @return the answer, {@link #NXDOMAIN} when the name matches a peer but
     *         {@code sourcePeer} isn't allowed to resolve it, or {@code null}
     *         when this is not a peer-name query at all and the normal zone
     *         lookup should handle it
     */
    private Resolution resolvePeerByName(Settings s, String queriedName, Peer sourcePeer, boolean bypassOwnershipCheck) {
        if (!s.dnsResolverEnabled) return null;
        String zone = normalizeZone(s.dnsResolverZone);
        String name = normalizeName(queriedName);
        if (name.equals(zone) || !name.endsWith("." + zone)) return null;
        String label = name.substring(0, name.length() - zone.length() - 1);
        if (label.contains(".")) return null; // peers only ever resolve as a single top-level label

        Peer match = findPeerByDnsLabel(label);
        if (match == null) return null;
        if (!bypassOwnershipCheck && !peerNameVisibleTo(match, sourcePeer, s)) return NXDOMAIN;
        return new Resolution.Answer(match.assignedIp, name, null);
    }

    /** The actual gate behind {@link #resolvePeerByName} — split out so its
     *  own doc comment can carry the reasoning without crowding the method
     *  that also does label-parsing. */
    private boolean peerNameVisibleTo(Peer target, Peer sourcePeer, Settings s) {
        if (s.dnsResolveAllResourcesAndPeers) return true;
        if (target.userId == null) return true; // site/gateway peer — infrastructure, not a personal device
        if (sourcePeer == null || sourcePeer.userId == null) return false; // no identity to check a share against
        if (target.userId.equals(sourcePeer.userId)) return true; // the owner's own other devices
        return PeerSelfShare.find(
                "ownerPeerId = ?1 and targetUserId = ?2 and revokedAt is null and validUntil > ?3",
                target.id, sourcePeer.userId, Instant.now()).firstResult() != null;
    }

    /**
     * Every peer's DNS label, deduplicated: {@link #slugify}(name), with
     * {@code -2}, {@code -3}, ... appended for each further peer whose name
     * slugifies the same way, oldest peer first (stable — a later rename or
     * new peer never renumbers an earlier one). {@value #HUB_LABEL} is
     * reserved for the hub itself: a peer that happens to slugify to it is
     * treated as if position 0 were already taken, so it starts at
     * {@code hub-2} rather than silently never resolving (the hub's own
     * fixed record always wins an exact "hub" query, checked before this
     * method ever runs — see {@link #resolve}).
     *
     * <p>Recomputed on every query rather than stored, same MVP trade-off
     * {@link #slugify} already accepts for site subdomains — see that
     * method's own doc comment.
     */
    private static Peer findPeerByDnsLabel(String label) {
        for (Map.Entry<Peer, String> e : peerDnsLabels().entrySet()) {
            if (e.getValue().equals(label)) return e.getKey();
        }
        return null;
    }

    /** This peer's own deduplicated label — see {@link #peerDnsLabels()}. */
    private static String peerDnsLabel(Peer peer) {
        return peerDnsLabels().get(peer);
    }

    /** Builds the full peer→label map in one pass, so {@link #findPeerByDnsLabel}
     *  and {@link #peerDnsLabel} (query-direction and listing-direction) can
     *  never disagree about who owns which label — see that method's own doc
     *  comment for the numbering rule itself. */
    private static Map<Peer, String> peerDnsLabels() {
        Map<String, List<Peer>> bySlug = new HashMap<>();
        for (Peer p : Peer.<Peer>listAll()) {
            bySlug.computeIfAbsent(slugify(p.name), k -> new ArrayList<>()).add(p);
        }
        Map<Peer, String> labels = new HashMap<>();
        for (Map.Entry<String, List<Peer>> e : bySlug.entrySet()) {
            String base = e.getKey();
            List<Peer> group = e.getValue();
            group.sort(Comparator.comparing(p -> p.createdAt));
            boolean baseReserved = base.equals(HUB_LABEL);
            for (int i = 0; i < group.size(); i++) {
                int ordinal = baseReserved ? i + 2 : i + 1;
                labels.put(group.get(i), ordinal == 1 ? base : base + "-" + ordinal);
            }
        }
        return labels;
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

    /** Full DNS name (e.g. {@code "laptop-2.islandr.internal"}) for each of
     *  {@code peers}, keyed by {@link Peer#id} — myaccess-peer-dns-name-display,
     *  so "Mein Zugang" can show a device's resolvable name without
     *  reimplementing the dedupe-numbering ({@link #peerDnsLabels}) in the
     *  frontend. Empty when the resolver is disabled (nothing would actually
     *  answer for any of these names). A peer with no entry in the result
     *  simply has no computed label — should not happen in practice, since
     *  {@link #peerDnsLabels} assigns one to every peer in the table, but
     *  callers must not assume every id in {@code peers} comes back.
     *
     *  <p>One {@link #peerDnsLabels} build total, not one per peer — that
     *  method keys its map by peer <em>identity</em> ({@link Peer} doesn't
     *  override {@code equals}/{@code hashCode}), so its own fresh
     *  {@code Peer.listAll()} instances can't be looked up with the
     *  caller-supplied {@code peers} list directly; re-keying by id here is
     *  what makes that safe. */
    @Transactional
    public Map<String, String> peerDnsFqdns(List<Peer> peers) {
        Settings s = settingsSvc.get();
        if (!s.dnsResolverEnabled) return Map.of();
        String zone = normalizeZone(s.dnsResolverZone);
        Map<String, String> labelsById = new HashMap<>();
        for (Map.Entry<Peer, String> e : peerDnsLabels().entrySet()) {
            labelsById.put(e.getKey().id, e.getValue());
        }
        Map<String, String> out = new HashMap<>();
        for (Peer p : peers) {
            String label = labelsById.get(p.id);
            if (label != null) out.put(p.id, label + "." + zone);
        }
        return out;
    }

    /** Count of resources with a DNS name set, plus every peer
     *  (peer-dns-name) — the "N resolvable names" stat on the System → DNS
     *  page. Not scoped by zone/site since there's only ever one managed zone
     *  per install (ADR-0023). */
    @Transactional
    public long resolvableCount() {
        return Resource.count("dnsName is not null") + Peer.count() + hubLabels(settingsSvc.get()).size();
    }

    /** One entry in {@link #resolvableNames()} — the FQDN plus which of the
     *  three disjoint sources it came from, so the System → DNS page can show
     *  that without the admin having to parse the name itself
     *  (dns-resolvable-names-typed). */
    public enum ResolvableNameKind { HUB, PEER, RESOURCE }

    /** @param fqdn the full name, exactly as {@link #resolvableNames()} used
     *              to return it (unchanged shape, now paired with a kind)
     *  @param kind which of hub/peer/resource this name belongs to */
    public record ResolvableName(String fqdn, ResolvableNameKind kind) {}

    /** Full FQDN for every resource with a DNS name set, plus every peer
     *  (peer-dns-name) — lets the System → DNS page show the admin the exact
     *  string to test instead of leaving them to derive the site slug, or a
     *  peer's deduplicated label, by hand (the German-umlaut slugify fix
     *  above is the kind of mismatch this sidesteps entirely). Sorted for a
     *  stable display order; admin-facing only — never touches ACL, same as
     *  the rest of this page's status data.
     *
     *  <p>Returns the FQDN paired with its {@link ResolvableNameKind}
     *  (dns-resolvable-names-typed) — the three sources below (hub labels,
     *  the resource loop, the peer loop) already know which is which while
     *  building the list; this just keeps that instead of flattening it away
     *  into a bare {@code List<String>} as before. */
    @Transactional
    public List<ResolvableName> resolvableNames() {
        Settings s = settingsSvc.get();
        String zone = normalizeZone(s.dnsResolverZone);
        List<Resource> named = Resource.<Resource>list("dnsName is not null");
        List<ResolvableName> fqdns = new ArrayList<>();
        for (String hub : hubLabels(s)) {
            fqdns.add(new ResolvableName(hub, ResolvableNameKind.HUB));
        }
        for (Resource r : named) {
            if (r.dnsFlat) {
                fqdns.add(new ResolvableName(canonicalFqdn(r, null, zone), ResolvableNameKind.RESOURCE));
                continue;
            }
            Site site = Site.findById(r.siteId);
            if (site == null) continue; // orphaned row, shouldn't happen — skip rather than throw
            fqdns.add(new ResolvableName(canonicalFqdn(r, site, zone), ResolvableNameKind.RESOURCE));
        }
        for (Peer p : Peer.<Peer>listAll()) {
            fqdns.add(new ResolvableName(peerDnsLabel(p) + "." + zone, ResolvableNameKind.PEER));
        }
        fqdns.sort(Comparator.comparing(ResolvableName::fqdn));
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
