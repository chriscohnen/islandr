package de.chriscohnen.islandr.acl;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class RoleNetworkGrantTest {

    @AfterEach
    @Transactional
    void cleanup() {
        RoleNetworkGrant.deleteAll();
        Role.deleteAll();
        Site.deleteAll();
    }

    @Test
    @Transactional
    void createNew_thenFindByRoleSite_returnsIt() {
        Role role = Role.createNew("NetGrantRole-" + UUID.randomUUID(), null);
        role.persist();
        Site site = Site.createNew("NetGrantSite-" + UUID.randomUUID(), "10.90.0.0/16", null);
        site.persist();

        RoleNetworkGrant g = RoleNetworkGrant.createNew(role.id, site.id);
        g.persist();

        RoleNetworkGrant found = RoleNetworkGrant.findByRoleSite(role.id, site.id);
        assertThat(found).isNotNull();
        assertThat(found.id).isEqualTo(g.id);
    }

    @Test
    @Transactional
    void findByRoleSite_returnsNull_whenNoGrant() {
        assertThat(RoleNetworkGrant.findByRoleSite("no-such-role", "no-such-site")).isNull();
    }
}
