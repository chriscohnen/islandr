package de.chriscohnen.islandr.acl;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Resource.mac persistence + the derived (never-stored) vendor field (issue #76). */
@QuarkusTest
class ResourceServiceMacTest {

    @Inject ResourceService resources;

    private Site site(String suffix) {
        Site s = Site.createNew("MacSite-" + suffix, "10.87.0.0/24", null);
        s.persist();
        return s;
    }

    @Test
    @Transactional
    void create_persistsMac_lowercased() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(suffix);

        Resource r = resources.create(site.id, new ResourceDto.UpsertRequest(
                "Pi-" + suffix, "10.87.0.5", null, "computer", "", false, "B8:27:EB:00:11:22"));

        assertThat(r.mac).isEqualTo("b8:27:eb:00:11:22");
        ResourceDto.Response resp = ResourceDto.Response.from(r, java.util.List.of());
        assertThat(resp.vendor()).isEqualTo("Raspberry Pi Foundation");
    }

    @Test
    @Transactional
    void create_blankMac_staysNull() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(suffix);

        Resource r = resources.create(site.id, new ResourceDto.UpsertRequest(
                "NoMac-" + suffix, "10.87.0.6", null, "computer", "", false, ""));

        assertThat(r.mac).isNull();
        assertThat(ResourceDto.Response.from(r, java.util.List.of()).vendor()).isNull();
    }

    @Test
    @Transactional
    void update_changesMac() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(suffix);
        Resource r = resources.create(site.id, new ResourceDto.UpsertRequest(
                "Update-" + suffix, "10.87.0.7", null, "computer", "", false, null));

        Resource updated = resources.update(r.id, new ResourceDto.UpsertRequest(
                "Update-" + suffix, "10.87.0.7", null, "computer", "", false, "aa:bb:cc:dd:ee:ff"));

        assertThat(updated.mac).isEqualTo("aa:bb:cc:dd:ee:ff");
    }
}
