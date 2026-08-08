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
 * Explicit DNS subdomain override on a site/network (ADR-0023 follow-up) —
 * decouples the resolver's site label from the display name. Resolver-side
 * matching behavior lives in {@code DnsQueryHandlerTest}; this covers the
 * save-time contract (normalization, global uniqueness).
 */
@QuarkusTest
class SiteServiceSubdomainTest {

    @Inject SiteService siteSvc;

    @Test
    @Transactional
    void create_persistsAnExplicitSubdomain() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = siteSvc.create(new SiteDto.UpsertRequest(
                "Site-" + suffix, "10.30.0.0/24", null, null, "custom-" + suffix, null));

        assertThat(site.subdomain).isEqualTo("custom-" + suffix);
    }

    @Test
    @Transactional
    void create_blankSubdomainStaysNull_derivedLiveInstead() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = siteSvc.create(new SiteDto.UpsertRequest(
                "Site-" + suffix, "10.31.0.0/24", null, null, "", null));

        assertThat(site.subdomain).isNull();
    }

    @Test
    @Transactional
    void create_rejectsADuplicateSubdomain_globallyAcrossSites() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String subdomain = "shared-" + suffix;
        siteSvc.create(new SiteDto.UpsertRequest("First-" + suffix, "10.32.0.0/24", null, null, subdomain, null));

        assertThatThrownBy(() -> siteSvc.create(new SiteDto.UpsertRequest(
                "Second-" + suffix, "10.33.0.0/24", null, null, subdomain, null)))
                .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @Transactional
    void update_keepingItsOwnSubdomain_doesNotSelfConflict() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String subdomain = "stable-" + suffix;
        Site site = siteSvc.create(new SiteDto.UpsertRequest("Site-" + suffix, "10.34.0.0/24", null, null, subdomain, null));

        Site updated = siteSvc.update(site.id, new SiteDto.UpsertRequest(
                "Renamed-" + suffix, "10.34.0.0/24", "now with a description", null, subdomain, null));

        assertThat(updated.subdomain).isEqualTo(subdomain);
    }
}
