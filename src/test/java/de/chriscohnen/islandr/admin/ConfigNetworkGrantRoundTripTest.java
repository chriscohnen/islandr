package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleBootstrap;
import de.chriscohnen.islandr.acl.RoleNetworkGrant;
import de.chriscohnen.islandr.acl.Site;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole-network role grants (#78, ADR-0029) must survive an export/import
 * round trip the same way every other ACL grant kind already does — see
 * ConfigService's own "tolerate absence" pattern for TypeGrantSnapshot etc.
 */
@QuarkusTest
class ConfigNetworkGrantRoundTripTest {

    @Inject ConfigService configService;
    @Inject RoleBootstrap roleBootstrap;

    @Test
    void export_thenImport_roundTripsNetworkGrant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String[] ids = QuarkusTransaction.requiringNew().call(() -> {
            Role role = Role.createNew("RoundTripRole-" + suffix, null);
            role.persist();
            Site site = Site.createNew("RoundTripSite-" + suffix, "10.97.0.0/16", null);
            site.persist();
            RoleNetworkGrant.createNew(role.id, site.id).persist();
            return new String[] { role.id, site.id };
        });
        String roleId = ids[0], siteId = ids[1];

        ConfigExportDto.Export exported = QuarkusTransaction.requiringNew().call(() -> configService.export(false));
        assertThat(exported.roleNetworkGrants()).anySatisfy(g -> {
            assertThat(g.roleId()).isEqualTo(roleId);
            assertThat(g.siteId()).isEqualTo(siteId);
        });

        QuarkusTransaction.requiringNew().run(() -> configService.importConfig(exported));

        boolean stillPresent = QuarkusTransaction.requiringNew().call(() ->
                RoleNetworkGrant.findByRoleSite(roleId, siteId) != null);
        assertThat(stillPresent).isTrue();

        // Cleanup — importConfig's own teardown wipes and fully rebuilds every
        // ACL table from the export, so nothing extra to clean beyond
        // reseeding the auto_all "Everyone" role the teardown removes.
        QuarkusTransaction.requiringNew().run(roleBootstrap::seedEveryoneRole);
    }
}
