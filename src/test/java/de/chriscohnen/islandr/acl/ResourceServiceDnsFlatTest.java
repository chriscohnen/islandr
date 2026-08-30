package de.chriscohnen.islandr.acl;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dnsName uniqueness scoping for the flat (no-subdomain) opt-out (ADR-0023
 * follow-up) — a flat name has no site label to disambiguate it, so its
 * uniqueness domain is the whole install, not just its own site, unlike a
 * regular (non-flat) dnsName. Resolver-side matching is covered separately
 * in {@code DnsQueryHandlerTest}.
 */
@QuarkusTest
class ResourceServiceDnsFlatTest {

    @Inject ResourceService resources;

    private Site site(String suffix) {
        Site s = Site.createNew("Site-" + suffix, "10.85.0.0/24", null);
        s.persist();
        return s;
    }

    private Site otherSite(String suffix) {
        Site s = Site.createNew("OtherSite-" + suffix, "10.86.0.0/24", null);
        s.persist();
        return s;
    }

    @Test
    @Transactional
    void create_rejectsADuplicateFlatName_evenAcrossDifferentSites() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dnsName = "flat-" + suffix;
        Site siteA = site(suffix);
        Site siteB = otherSite(suffix);

        resources.create(siteA.id, new ResourceDto.UpsertRequest(
                "A-" + suffix, "10.85.0.5", null, "computer", dnsName, true, null, null, true));

        assertThatThrownBy(() -> resources.create(siteB.id, new ResourceDto.UpsertRequest(
                "B-" + suffix, "10.86.0.5", null, "computer", dnsName, true, null, null, true)))
                .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @Transactional
    void create_allowsTheSameNameForANonFlatResourceInADifferentSite() {
        // Regular (non-flat) dnsName uniqueness stays per-site — unaffected by
        // the flat pool being global. Two different sites, same name, neither flat.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dnsName = "shared-" + suffix;
        Site siteA = site(suffix);
        Site siteB = otherSite(suffix);

        Resource a = resources.create(siteA.id, new ResourceDto.UpsertRequest(
                "A-" + suffix, "10.85.0.6", null, "computer", dnsName, false, null, null, true));
        Resource b = resources.create(siteB.id, new ResourceDto.UpsertRequest(
                "B-" + suffix, "10.86.0.6", null, "computer", dnsName, false, null, null, true));

        assertThat(a.dnsName).isEqualTo(dnsName);
        assertThat(b.dnsName).isEqualTo(dnsName);
    }

    @Test
    @Transactional
    void create_allowsAFlatAndNonFlatResourceToShareTheSameName() {
        // Different uniqueness domains (global vs per-site) — their resolved
        // FQDN shapes differ ("<name>.<zone>" vs "<name>.<site>.<zone>"), so
        // they never actually collide on the wire.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dnsName = "dual-" + suffix;
        Site site = site(suffix);

        Resource flat = resources.create(site.id, new ResourceDto.UpsertRequest(
                "Flat-" + suffix, "10.85.0.7", null, "computer", dnsName, true, null, null, true));
        Resource nonFlat = resources.create(site.id, new ResourceDto.UpsertRequest(
                "NonFlat-" + suffix, "10.85.0.8", null, "computer", dnsName, false, null, null, true));

        assertThat(flat.dnsFlat).isTrue();
        assertThat(nonFlat.dnsFlat).isFalse();
        assertThat(flat.dnsName).isEqualTo(nonFlat.dnsName);
    }

    @Test
    @Transactional
    void dnsFlat_isIgnoredWhenDnsNameIsBlank() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(suffix);

        Resource r = resources.create(site.id, new ResourceDto.UpsertRequest(
                "NoName-" + suffix, "10.85.0.9", null, "computer", "", true, null, null, true));

        assertThat(r.dnsName).isNull();
        assertThat(r.dnsFlat).isFalse();
    }

    @Test
    @Transactional
    void update_togglingToFlat_checksTheGlobalPool() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dnsName = "toggle-" + suffix;
        Site siteA = site(suffix);
        Site siteB = otherSite(suffix);

        // An existing flat resource claims the name globally...
        resources.create(siteA.id, new ResourceDto.UpsertRequest(
                "A-" + suffix, "10.85.0.10", null, "computer", dnsName, true, null, null, true));

        // ...a second, non-flat resource with the same name is fine to create...
        Resource nonFlat = resources.create(siteB.id, new ResourceDto.UpsertRequest(
                "B-" + suffix, "10.86.0.10", null, "computer", dnsName, false, null, null, true));

        // ...but flipping it to flat now collides with the first one.
        assertThatThrownBy(() -> resources.update(nonFlat.id, new ResourceDto.UpsertRequest(
                "B-" + suffix, "10.86.0.10", null, "computer", dnsName, true, null, null, true)))
                .isInstanceOf(WebApplicationException.class);
    }
}
