package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerSelfShare;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zone-match / ACL-filter core of the DNS resolver (ADR-0023) — every
 * branch of {@link DnsQueryHandler#resolve}, independent of sockets/wire
 * format (covered separately by {@code DnsWireFormatTest}).
 */
@QuarkusTest
class DnsQueryHandlerTest {

    @Inject DnsQueryHandler handler;
    @PersistenceContext EntityManager em;

    private static final String ZONE = "islandr.internal";

    // Settings is a shared singleton across the whole test suite (established
    // precedent — see e.g. PeerResourceTest#setGlobalDns, which doesn't restore
    // either). Unlike those inert fields, dnsResolverEnabled has a real side
    // effect elsewhere (DnsResolverService.reconcile() attempts a socket bind
    // whenever any test hits PUT /api/v1/settings) — so this class resets it,
    // to avoid leaving every later settings-touching test attempting a bind.
    //
    // Both @BeforeEach and @AfterEach, not just the latter: dnsResolveAllResourcesAndPeers
    // defaults to true at the DB level (dns-resolve-all-resources-option is
    // opt-out), so whichever test JUnit happens to run first in this class
    // would otherwise see the grant check silently waived before any
    // @AfterEach from a prior test had a chance to turn it back off — JUnit5's
    // default test order is not declaration order. @BeforeEach removes that
    // ordering dependency entirely.
    @BeforeEach
    @AfterEach
    @Transactional
    void resetResolverFlag() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
        s.dnsResolverUpstream = null;
        s.dnsResolveAllResourcesAndPeers = false;
        s.wgClientDns = null;
        // V3's seeded default — one of this class's own tests (the hub-name-
        // collision one) overwrites it to get a predictable hub answer; left
        // unreset it broke every other test's assumption about the default
        // subnet (e.g. PeerResourceTest#nextIp_returnsAddressInsideSubnet).
        s.wgSubnet = "10.8.0.0/24";
    }

    private record Fixture(String siteSlug, String resourceDnsName, String resourceIp,
                           String grantedPeerIp, String ungrantedPeerIp, String noIdentityPeerIp,
                           String resourceId, String ungrantedUserId) {}

    @Transactional
    Fixture seed(boolean viaAutoAllRole) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;

        User granted = User.createNew("Granted " + suffix, "granted-" + suffix + "@firma.de");
        granted.persist();
        User ungranted = User.createNew("Ungranted " + suffix, "ungranted-" + suffix + "@firma.de");
        ungranted.persist();

        Role role = Role.createNew("DnsRole-" + suffix, null);
        role.autoAll = viaAutoAllRole;
        role.persist();
        if (!viaAutoAllRole) {
            em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                    .setParameter(1, granted.id).setParameter(2, role.id).executeUpdate();
        }

        String siteName = "Homeoffice-" + suffix;
        Site site = Site.createNew(siteName, "10.70.0.0/24", null);
        site.persist();

        // Suffixed, not a shared literal like "fileserver" — the bare-name
        // admin-preview shortcut resolves by dnsName across the WHOLE table
        // (shared across the suite, no per-test isolation), so a fixed literal
        // would go ambiguous the moment two tests' seed() calls coexist.
        String dnsName = "fileserver-" + suffix;
        Resource resource = Resource.createNew(site.id, "Fileserver-" + suffix, "10.70.0.5", null, "computer");
        resource.dnsName = dnsName;
        resource.persist();

        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, resource.id, true);
        grant.persist();

        // Peer.assignedIp is globally unique across the whole DB (shared with
        // every other test class), so these must be deterministic, not random
        // — a Math.random() range here collided under a full-suite run.
        String grantedIp = nextTestIp();
        String ungrantedIp = nextTestIp();
        String noIdentityIp = nextTestIp();
        Peer.createNew(viaAutoAllRole ? ungranted.id : granted.id, "granted-peer-" + suffix, fakeKey(suffix, "g"), grantedIp).persist();
        Peer.createNew(ungranted.id, "ungranted-peer-" + suffix, fakeKey(suffix, "u"), ungrantedIp).persist();
        // No Peer row at all for noIdentityIp — simulates an unrecognized source.

        return new Fixture(DnsQueryHandler.slugify(siteName), dnsName, resource.ip,
                grantedIp, ungrantedIp, noIdentityIp, resource.id, ungranted.id);
    }

    private static final java.util.concurrent.atomic.AtomicInteger IP_COUNTER = new java.util.concurrent.atomic.AtomicInteger(1);

    private static String nextTestIp() {
        int n = IP_COUNTER.getAndIncrement();
        return "10.199." + (n / 250) + "." + (1 + n % 250);
    }

    private static String fakeKey(String suffix, String tag) {
        String raw = ("KEY" + tag + suffix).repeat(4);
        return raw.substring(0, 43) + "=";
    }

    private String fqdn(Fixture f) {
        return f.resourceDnsName() + "." + f.siteSlug() + "." + ZONE;
    }

    @Test
    void resolve_answersWithResourceIp_whenPeerHasConcreteGrant() {
        Fixture f = seed(false);
        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.grantedPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.resourceIp());
    }

    @Test
    void resolve_answers_whenGrantComesFromAnAutoAllRole() {
        Fixture f = seed(true);
        // grantedPeerIp here belongs to "ungranted" user, who nonetheless gets
        // the auto_all role's grant (ADR-0013 "Everyone" semantics).
        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.grantedPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
    }

    // ADR-0024's direct User→Resource grant — the "Freigabe hinzufügen" path
    // in the ACL matrix's add-user-grant dialog — bypasses the role model
    // entirely. AclService.hasAnyGrant (used by #resolve) only ever checked
    // role_resource_grants/role_resource_type_grants; a resource granted
    // *only* this way looked identical to "no grant at all" to the DNS
    // resolver, even though the admin console's own ACL page showed it as
    // granted.
    @Test
    void resolve_answers_whenGrantIsADirectUserGrant_notThroughARole() {
        Fixture f = seed(false);
        // ungrantedPeerIp's user has no role-based grant at all (seed() never
        // adds one) — give it a *direct* grant instead.
        directGrant(f.ungrantedUserId(), f.resourceId());

        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.ungrantedPeerIp());

        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.resourceIp());
    }

    @Transactional
    void directGrant(String userId, String resourceId) {
        UserResourceGrant.createNew(userId, resourceId, true).persist();
    }

    @Test
    void resolve_isNxDomain_whenPeerHasNoGrant() {
        Fixture f = seed(false);
        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.ungrantedPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    @Transactional
    void resolve_answersEvenWithoutAGrant_whenResolveAllResourcesIsEnabled() {
        Fixture f = seed(false);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolveAllResourcesAndPeers = true;

        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.ungrantedPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.resourceIp());
    }

    // The option only waives the *grant* check — an unrecognized source still
    // isn't anyone this resolver can answer for at all (no identity to have
    // waived a grant on in the first place).
    @Test
    @Transactional
    void resolve_stillNxDomain_whenResolveAllResourcesEnabled_butSourceHasNoPeer() {
        Fixture f = seed(false);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolveAllResourcesAndPeers = true;

        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.noIdentityPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void resolve_isNxDomain_whenSourceIpHasNoPeer() {
        Fixture f = seed(false);
        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.noIdentityPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void resolve_isNxDomain_forUnknownResourceInAKnownSite() {
        Fixture f = seed(false);
        String name = "does-not-exist." + f.siteSlug() + "." + ZONE;
        assertThat(handler.resolve(name, f.grantedPeerIp())).isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void resolve_isNxDomain_forUnknownSite() {
        Fixture f = seed(false);
        String name = f.resourceDnsName() + ".no-such-site." + ZONE;
        assertThat(handler.resolve(name, f.grantedPeerIp())).isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void resolve_isNotManaged_forNamesOutsideTheZone() {
        Fixture f = seed(false);
        assertThat(handler.resolve("example.com", f.grantedPeerIp()))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Test
    @Transactional
    void resolve_isNotManaged_whenResolverDisabled() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        assertThat(handler.resolve("fileserver.homeoffice." + ZONE, "10.9.0.2"))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Test
    @Transactional
    void currentConfig_fallsBackToDefaultUpstreams_whenNoneConfigured() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverUpstream = null;
        assertThat(handler.currentConfig().upstreams()).isEqualTo(DnsQueryHandler.DEFAULT_UPSTREAMS);
    }

    @Test
    @Transactional
    void currentConfig_usesConfiguredUpstreams_independentlyOfWgClientDns() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        // A split-DNS token here would be meaningless as a forward target and
        // must not leak into the resolver's own upstream list — the two
        // fields are deliberately independent (see Settings.java).
        s.wgClientDns = "~internal-only-token";
        s.dnsResolverUpstream = "9.9.9.9, 8.8.4.4";
        assertThat(handler.currentConfig().upstreams()).containsExactly("9.9.9.9", "8.8.4.4");
    }

    @Test
    void slugify_lowercasesAndHyphenatesSiteNames() {
        assertThat(DnsQueryHandler.slugify("Homeoffice Berlin")).isEqualTo("homeoffice-berlin");
        assertThat(DnsQueryHandler.slugify("  Multi   Space  ")).isEqualTo("multi-space");
    }

    @Test
    void slugify_transliteratesGermanUmlautsInsteadOfDroppingThem() {
        // Every non-ASCII letter individually collapsing to its own hyphen
        // ("b-ro-d-sseldorf") would be both ugly and lossy — two differently
        // named sites could end up with the same slug once their umlauts vanish.
        assertThat(DnsQueryHandler.slugify("Büro Düsseldorf")).isEqualTo("buero-duesseldorf");
        assertThat(DnsQueryHandler.slugify("Größe")).isEqualTo("groesse");
        assertThat(DnsQueryHandler.slugify("Straße")).isEqualTo("strasse");
    }

    @Test
    void slugify_deaccentsOtherLatinLetters() {
        assertThat(DnsQueryHandler.slugify("Café Zürich")).isEqualTo("cafe-zuerich");
    }

    @Test
    void adminPreview_answersWithoutRequiringAConnectedPeer() {
        Fixture f = seed(false);
        // The ungranted peer's IP would get NXDOMAIN via #resolve (ACL-gated) —
        // the whole point of the admin preview is that identity doesn't matter.
        DnsQueryHandler.Resolution r = handler.resolveForAdminPreview(fqdn(f));
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.resourceIp());
    }

    @Test
    void adminPreview_appendsTheZone_whenMissing() {
        Fixture f = seed(false);
        String withoutZone = f.resourceDnsName() + "." + f.siteSlug(); // no ".islandr.internal"
        DnsQueryHandler.Resolution r = handler.resolveForAdminPreview(withoutZone);
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
    }

    @Test
    void adminPreview_doesNotZoneAppend_aFullyQualifiedExternalDomain() {
        // Regression: "www.google.de" (2+ dots) used to get the zone appended
        // ("www.google.de.islandr.internal"), parse as resource "www.google"
        // in site "de", get rejected for extra depth, and land on NXDOMAIN —
        // reporting a plainly external name as "inside the zone, no match"
        // instead of "not managed, would be forwarded".
        Fixture f = seed(false);
        assertThat(handler.resolveForAdminPreview("www.google.de"))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Test
    void adminPreview_resolvesABareName_whenExactlyOneResourceMatches() {
        Fixture f = seed(false);
        DnsQueryHandler.Resolution r = handler.resolveForAdminPreview(f.resourceDnsName());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.resourceIp());
    }

    @Test
    @Transactional
    void adminPreview_isNxDomain_forABareNameMatchingMultipleResources() {
        Fixture f = seed(false);
        // A second, differently-sited resource with the same dnsName — the
        // bare-name shortcut must not guess between them.
        Site otherSite = Site.createNew("Other-" + UUID.randomUUID().toString().substring(0, 8), "10.80.0.0/24", null);
        otherSite.persist();
        Resource other = Resource.createNew(otherSite.id, "Fileserver2", "10.80.0.5", null, "computer");
        other.dnsName = f.resourceDnsName();
        other.persist();

        assertThat(handler.resolveForAdminPreview(f.resourceDnsName()))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void resolvableNames_listsFullFqdnsForEveryNamedResource() {
        Fixture f = seed(false);
        assertThat(handler.resolvableNames())
                .extracting(DnsQueryHandler.ResolvableName::fqdn)
                .contains(fqdn(f));
        assertThat(handler.resolvableNames())
                .filteredOn(n -> n.fqdn().equals(fqdn(f)))
                .extracting(DnsQueryHandler.ResolvableName::kind)
                .containsExactly(DnsQueryHandler.ResolvableNameKind.RESOURCE);
    }

    @Test
    @Transactional
    void resolvableNames_listsTheHub() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.wgSubnet = "10.202.0.0/24";

        String hubFqdn = DnsQueryHandler.HUB_LABEL + "." + ZONE;
        assertThat(handler.resolvableNames())
                .extracting(DnsQueryHandler.ResolvableName::fqdn)
                .contains(hubFqdn);
        assertThat(handler.resolvableNames())
                .filteredOn(n -> n.fqdn().equals(hubFqdn))
                .extracting(DnsQueryHandler.ResolvableName::kind)
                .containsExactly(DnsQueryHandler.ResolvableNameKind.HUB);
    }

    @Test
    @Transactional
    void resolvableNames_listsTheHubAlias_whenSet() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.wgSubnet = "10.203.0.0/24";
        s.dnsHubAlias = "console.example.com";

        assertThat(handler.resolvableNames())
                .extracting(DnsQueryHandler.ResolvableName::fqdn)
                .contains(DnsQueryHandler.HUB_LABEL + "." + ZONE, "console.example.com");
    }

    @Test
    @Transactional
    void resolvableNames_omitsTheHub_whenNoTunnelAddressIsAvailable() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.wgSubnet = ""; // no address to answer "hub." with — same guard resolveHub() itself uses (wg_subnet is NOT NULL, blank is the closest "unset")

        assertThat(handler.resolvableNames())
                .extracting(DnsQueryHandler.ResolvableName::fqdn)
                .doesNotContain(DnsQueryHandler.HUB_LABEL + "." + ZONE);
    }

    @Test
    @Transactional
    void resolvableCount_includesTheHub() {
        long resourcesAndPeers = Resource.count("dnsName is not null") + Peer.count();
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.wgSubnet = "10.204.0.0/24";

        assertThat(handler.resolvableCount()).isEqualTo(resourcesAndPeers + 1);
    }

    // ---- Explicit site subdomain override (ADR-0023 follow-up) -------------

    @Test
    @Transactional
    void resolve_usesExplicitSubdomain_insteadOfTheDerivedSlug() {
        Fixture f = seed(false);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String explicit = "custom-sub-" + suffix;
        Site site = findSite(f.siteSlug());
        site.subdomain = explicit;

        // The derived slug no longer matches once an explicit override is set.
        assertThat(handler.resolve(f.resourceDnsName() + "." + f.siteSlug() + "." + ZONE, f.grantedPeerIp()))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);

        DnsQueryHandler.Resolution r = handler.resolve(
                f.resourceDnsName() + "." + explicit + "." + ZONE, f.grantedPeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).fqdn())
                .isEqualTo(f.resourceDnsName() + "." + explicit + "." + ZONE);
    }

    private static Site findSite(String slug) {
        return Site.<Site>listAll().stream()
                .filter(s -> DnsQueryHandler.slugify(s.name).equals(slug))
                .findFirst().orElseThrow();
    }

    // ---- Flat resources: no subdomain layer (ADR-0023 follow-up) -----------

    @Test
    @Transactional
    void resolve_answersAFlatResource_directlyUnderTheZoneApex() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;

        User user = User.createNew("FlatUser-" + suffix, "flatuser-" + suffix + "@firma.de");
        user.persist();
        Role role = Role.createNew("FlatRole-" + suffix, null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();

        Site site = Site.createNew("FlatSite-" + suffix, "10.74.0.0/24", null);
        site.persist();
        String dnsName = "gateway-" + suffix;
        Resource resource = Resource.createNew(site.id, "Gateway-" + suffix, "10.74.0.1", null, "router");
        resource.dnsName = dnsName;
        resource.dnsFlat = true;
        resource.persist();
        RoleResourceGrant.createNew(role.id, resource.id, true).persist();

        String peerIp = nextTestIp();
        Peer.createNew(user.id, "flat-peer-" + suffix, fakeKey(suffix, "f"), peerIp).persist();

        DnsQueryHandler.Resolution r = handler.resolve(dnsName + "." + ZONE, peerIp);
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo("10.74.0.1");
        assertThat(((DnsQueryHandler.Resolution.Answer) r).fqdn()).isEqualTo(dnsName + "." + ZONE);

        // A flat resource has exactly one canonical FQDN — it must NOT also
        // answer under its own site's subdomain (that would be two aliases
        // for the same resource, not the point of the opt-out).
        assertThat(handler.resolve(dnsName + "." + DnsQueryHandler.slugify(site.name) + "." + ZONE, peerIp))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    // ---- Peer names (peer-dns-name) -----------------------------------------

    @Test
    @Transactional
    void resolve_answersAPeersOwnName_regardlessOfGrants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;

        String peerIp = nextTestIp();
        String peerName = "Laptop-" + suffix;
        Peer.createNew(null, peerName, fakeKey(suffix, "p"), peerIp).persist();

        // No user, no grant, no role at all — a peer's own name resolves
        // unconditionally, the same way the hub's does (see
        // DnsQueryHandler#resolvePeerByName).
        DnsQueryHandler.Resolution r = handler.resolve(
                DnsQueryHandler.slugify(peerName) + "." + ZONE, "10.0.0.254");
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(peerIp);
    }

    @Test
    @Transactional
    void resolve_dedupesCollidingPeerNames_oldestKeepsTheBaseLabel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;

        // Same slug ("iphone-<suffix>") on purpose — two different people's
        // devices sharing a common name is entirely normal, unlike a
        // resource's admin-curated dnsName. The older peer keeps the plain
        // label, the newer one gets "-2", nobody is rejected outright.
        String name = "iPhone-" + suffix;
        String firstIp = nextTestIp();
        String secondIp = nextTestIp();
        Peer first = Peer.createNew(null, name, fakeKey(suffix, "a"), firstIp);
        first.createdAt = java.time.Instant.now().minusSeconds(60);
        first.persist();
        Peer second = Peer.createNew(null, name, fakeKey(suffix, "b"), secondIp);
        second.createdAt = java.time.Instant.now();
        second.persist();

        String base = DnsQueryHandler.slugify(name);
        DnsQueryHandler.Resolution r1 = handler.resolve(base + "." + ZONE, "10.0.0.254");
        assertThat(((DnsQueryHandler.Resolution.Answer) r1).ip()).isEqualTo(firstIp);

        DnsQueryHandler.Resolution r2 = handler.resolve(base + "-2." + ZONE, "10.0.0.254");
        assertThat(((DnsQueryHandler.Resolution.Answer) r2).ip()).isEqualTo(secondIp);
    }

    @Test
    @Transactional
    void resolve_aPeerNamedLikeTheHub_isPushedToHub2_hubRecordWins() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.wgSubnet = "10.201.0.0/24"; // gives the real hub an address to answer "hub." with

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String peerIp = nextTestIp();
        Peer.createNew(null, "hub", fakeKey(suffix, "h"), peerIp).persist();

        DnsQueryHandler.Resolution hubAnswer = handler.resolve("hub." + ZONE, "10.0.0.254");
        assertThat(((DnsQueryHandler.Resolution.Answer) hubAnswer).ip()).isEqualTo("10.201.0.1");

        DnsQueryHandler.Resolution peerAnswer = handler.resolve("hub-2." + ZONE, "10.0.0.254");
        assertThat(((DnsQueryHandler.Resolution.Answer) peerAnswer).ip()).isEqualTo(peerIp);
    }

    // ---- peer-dns-name-gated-by-self-share ----------------------------------

    private record OwnedPeerFixture(String targetName, String targetIp, String targetPeerId,
                                     String ownerUserId, String sourcePeerIp) {}

    @Transactional
    OwnedPeerFixture seedOwnedPeer(String suffix) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;

        User owner = User.createNew("Owner-" + suffix, "owner-gate-" + suffix + "@firma.de");
        owner.persist();
        String targetIp = nextTestIp();
        String targetName = "Gated-Peer-" + suffix;
        Peer target = Peer.createNew(owner.id, targetName, fakeKey(suffix, "t"), targetIp);
        target.persist();
        // An unrelated peer with no identity relationship to the target at
        // all — the "fremder Peer" the acceptance criterion names.
        String sourceIp = nextTestIp();
        return new OwnedPeerFixture(targetName, targetIp, target.id, owner.id, sourceIp);
    }

    @Test
    void resolve_ownedPeerName_isNxDomain_forAnUnrelatedPeer() {
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        assertThat(handler.resolve(DnsQueryHandler.slugify(f.targetName()) + "." + ZONE, f.sourcePeerIp()))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    @Transactional
    void resolve_ownedPeerName_resolvesForOwnersOtherDevice() {
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        String ownersOtherDeviceIp = nextTestIp();
        Peer.createNew(f.ownerUserId(), "Owner-Other-Device", fakeKey("od" + f.targetPeerId(), "o"), ownersOtherDeviceIp).persist();

        DnsQueryHandler.Resolution r = handler.resolve(
                DnsQueryHandler.slugify(f.targetName()) + "." + ZONE, ownersOtherDeviceIp);
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.targetIp());
    }

    @Test
    @Transactional
    void resolve_ownedPeerName_resolvesForUserWithLiveSelfShare() {
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        User colleague = User.createNew("Colleague", "colleague-gate@firma.de");
        colleague.persist();
        String colleaguesDeviceIp = nextTestIp();
        Peer.createNew(colleague.id, "Colleague-Device", fakeKey("cd" + f.targetPeerId(), "c"), colleaguesDeviceIp).persist();
        PeerSelfShare.createNew(f.targetPeerId(), colleague.id, 3389, java.time.Instant.now().plusSeconds(3600)).persist();

        DnsQueryHandler.Resolution r = handler.resolve(
                DnsQueryHandler.slugify(f.targetName()) + "." + ZONE, colleaguesDeviceIp);
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.targetIp());
    }

    @Test
    @Transactional
    void resolve_ownedPeerName_isNxDomain_afterSelfShareExpires() {
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        User colleague = User.createNew("ExpiredColleague", "expired-colleague-gate@firma.de");
        colleague.persist();
        String colleaguesDeviceIp = nextTestIp();
        Peer.createNew(colleague.id, "Expired-Colleague-Device", fakeKey("ed" + f.targetPeerId(), "e"), colleaguesDeviceIp).persist();
        PeerSelfShare.createNew(f.targetPeerId(), colleague.id, 3389, java.time.Instant.now().minusSeconds(60)).persist();

        assertThat(handler.resolve(DnsQueryHandler.slugify(f.targetName()) + "." + ZONE, colleaguesDeviceIp))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    @Transactional
    void resolve_ownedPeerName_isNxDomain_afterSelfShareRevoked() {
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        User colleague = User.createNew("RevokedColleague", "revoked-colleague-gate@firma.de");
        colleague.persist();
        String colleaguesDeviceIp = nextTestIp();
        Peer.createNew(colleague.id, "Revoked-Colleague-Device", fakeKey("rd" + f.targetPeerId(), "r"), colleaguesDeviceIp).persist();
        PeerSelfShare share = PeerSelfShare.createNew(f.targetPeerId(), colleague.id, 3389, java.time.Instant.now().plusSeconds(3600));
        share.revokedAt = java.time.Instant.now();
        share.persist();

        assertThat(handler.resolve(DnsQueryHandler.slugify(f.targetName()) + "." + ZONE, colleaguesDeviceIp))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    @Transactional
    void resolve_ownedPeerName_resolvesForEveryone_whenResolveAllResourcesAndPeersIsOn() {
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolveAllResourcesAndPeers = true;

        DnsQueryHandler.Resolution r = handler.resolve(
                DnsQueryHandler.slugify(f.targetName()) + "." + ZONE, f.sourcePeerIp());
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.targetIp());
    }

    @Test
    void adminPreview_resolvesAnOwnedPeerName_regardlessOfOwnership() {
        // resolveForAdminPreview bypasses the ownership/self-share check
        // entirely, same as it already does for resource grants — an admin
        // isn't a connected peer and shouldn't need to fake being one.
        OwnedPeerFixture f = seedOwnedPeer(UUID.randomUUID().toString().substring(0, 8));
        DnsQueryHandler.Resolution r = handler.resolveForAdminPreview(DnsQueryHandler.slugify(f.targetName()) + "." + ZONE);
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(((DnsQueryHandler.Resolution.Answer) r).ip()).isEqualTo(f.targetIp());
    }

    @Test
    @Transactional
    void resolvableNames_listsAPeersOwnName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        String name = "Standalone-Peer-" + suffix;
        Peer.createNew(null, name, fakeKey(suffix, "n"), nextTestIp()).persist();

        String peerFqdn = DnsQueryHandler.slugify(name) + "." + ZONE;
        assertThat(handler.resolvableNames())
                .extracting(DnsQueryHandler.ResolvableName::fqdn)
                .contains(peerFqdn);
        assertThat(handler.resolvableNames())
                .filteredOn(n -> n.fqdn().equals(peerFqdn))
                .extracting(DnsQueryHandler.ResolvableName::kind)
                .containsExactly(DnsQueryHandler.ResolvableNameKind.PEER);
    }

    @Test
    @Transactional
    void peerDnsFqdns_returnsEachPeersFullName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        String name = "Fqdn-Peer-" + suffix;
        Peer p = Peer.createNew(null, name, fakeKey(suffix, "q"), nextTestIp());
        p.persist();

        java.util.Map<String, String> fqdns = handler.peerDnsFqdns(java.util.List.of(p));
        assertThat(fqdns).containsEntry(p.id, DnsQueryHandler.slugify(name) + "." + ZONE);
    }

    @Test
    @Transactional
    void peerDnsFqdns_isEmpty_whenResolverDisabled() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Peer p = Peer.createNew(null, "Disabled-Resolver-Peer-" + suffix, fakeKey(suffix, "d"), nextTestIp());
        p.persist();

        assertThat(handler.peerDnsFqdns(java.util.List.of(p))).isEmpty();
    }

    @Test
    void resolvableNames_listsAFlatResourceWithoutASubdomainLabel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dnsName = seedFlatResourceOnly(suffix);
        assertThat(handler.resolvableNames())
                .extracting(DnsQueryHandler.ResolvableName::fqdn)
                .contains(dnsName + "." + ZONE);
    }

    @Transactional
    String seedFlatResourceOnly(String suffix) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        Site site = Site.createNew("FlatOnly-" + suffix, "10.75.0.0/24", null);
        site.persist();
        String dnsName = "standalone-" + suffix;
        Resource resource = Resource.createNew(site.id, "Standalone-" + suffix, "10.75.0.1", null, "router");
        resource.dnsName = dnsName;
        resource.dnsFlat = true;
        resource.persist();
        return dnsName;
    }
}
