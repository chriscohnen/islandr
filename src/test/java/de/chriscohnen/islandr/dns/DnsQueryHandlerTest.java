package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
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
    @AfterEach
    @Transactional
    void resetResolverFlag() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
        s.dnsResolverUpstream = null;
        s.wgClientDns = null;
    }

    private record Fixture(String siteSlug, String resourceDnsName, String resourceIp,
                           String grantedPeerIp, String ungrantedPeerIp, String noIdentityPeerIp) {}

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
                grantedIp, ungrantedIp, noIdentityIp);
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

    @Test
    void resolve_isNxDomain_whenPeerHasNoGrant() {
        Fixture f = seed(false);
        DnsQueryHandler.Resolution r = handler.resolve(fqdn(f), f.ungrantedPeerIp());
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
        assertThat(handler.resolvableNames()).contains(fqdn(f));
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

    @Test
    void resolvableNames_listsAFlatResourceWithoutASubdomainLabel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dnsName = seedFlatResourceOnly(suffix);
        assertThat(handler.resolvableNames()).contains(dnsName + "." + ZONE);
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
