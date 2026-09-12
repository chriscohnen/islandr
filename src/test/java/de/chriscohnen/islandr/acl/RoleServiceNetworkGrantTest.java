package de.chriscohnen.islandr.acl;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class RoleServiceNetworkGrantTest {

    @Inject RoleService roles;

    @AfterEach
    @Transactional
    void cleanup() {
        Role.delete("name like ?1", "NetSvcRole%");
        Site.delete("name like ?1", "NetSvcSite%");
    }

    @Transactional
    Role persistRole(String name) {
        Role r = Role.createNew(name, null);
        r.persist();
        return r;
    }

    @Transactional
    Site persistSite(String name, String cidr) {
        Site s = Site.createNew(name, cidr, null);
        s.persist();
        return s;
    }

    @Test
    @Transactional
    void createNetworkGrant_thenList_returnsItWithSiteName() {
        Role role = persistRole("NetSvcRole-" + UUID.randomUUID());
        Site site = persistSite("NetSvcSite-" + UUID.randomUUID(), "10.91.0.0/16");

        RoleNetworkGrant g = roles.createNetworkGrant(new RoleDto.NetworkGrantRequest(role.id, site.id));

        assertThat(g.roleId).isEqualTo(role.id);
        assertThat(g.siteId).isEqualTo(site.id);

        java.util.List<RoleDto.NetworkGrantResponse> list = roles.listNetworkGrants();
        assertThat(list).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo(g.id);
            assertThat(r.siteName()).isEqualTo(site.name);
        });
    }

    @Test
    @Transactional
    void createNetworkGrant_sameRuleTwice_isIdempotent() {
        Role role = persistRole("NetSvcRole2-" + UUID.randomUUID());
        Site site = persistSite("NetSvcSite2-" + UUID.randomUUID(), "10.92.0.0/16");

        RoleNetworkGrant first = roles.createNetworkGrant(new RoleDto.NetworkGrantRequest(role.id, site.id));
        RoleNetworkGrant second = roles.createNetworkGrant(new RoleDto.NetworkGrantRequest(role.id, site.id));

        assertThat(second.id).isEqualTo(first.id);
        // Scoped to this test's own role/site rather than a global count —
        // the table isn't guaranteed empty otherwise, only incidentally so
        // depending on other tests' cleanup/ordering.
        assertThat(RoleNetworkGrant.<RoleNetworkGrant>list("roleId = ?1 and siteId = ?2", role.id, site.id)).hasSize(1);
    }

    @Test
    @Transactional
    void createNetworkGrant_unknownRole_throwsNotFound() {
        Site site = persistSite("NetSvcSite3-" + UUID.randomUUID(), "10.93.0.0/16");
        assertThatThrownBy(() -> roles.createNetworkGrant(new RoleDto.NetworkGrantRequest("not-a-role", site.id)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Transactional
    void createNetworkGrant_unknownSite_throwsNotFound() {
        Role role = persistRole("NetSvcRole4-" + UUID.randomUUID());
        assertThatThrownBy(() -> roles.createNetworkGrant(new RoleDto.NetworkGrantRequest(role.id, "not-a-site")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Transactional
    void deleteNetworkGrant_removesIt() {
        Role role = persistRole("NetSvcRole5-" + UUID.randomUUID());
        Site site = persistSite("NetSvcSite5-" + UUID.randomUUID(), "10.94.0.0/16");
        RoleNetworkGrant g = roles.createNetworkGrant(new RoleDto.NetworkGrantRequest(role.id, site.id));

        roles.deleteNetworkGrant(g.id);

        assertThat((Object) RoleNetworkGrant.findById(g.id)).isNull();
    }

    @Test
    @Transactional
    void deleteNetworkGrant_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> roles.deleteNetworkGrant("not-a-real-id"))
                .isInstanceOf(NotFoundException.class);
    }
}
