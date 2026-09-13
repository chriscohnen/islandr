package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #83 / R-171: the browser-RDP gate refused sessions the firewall and the
 * portal both allowed, because it resolved roles by joining {@code user_roles}
 * and so never saw the automatic "Everyone" role (auto_all, ADR-0013).
 *
 * <p>Every grant kind is checked through that role — concrete, port-scoped,
 * type (ADR-0022) and network (ADR-0029) — with the user holding no explicit
 * membership at all. A port-scoped grant on a <em>different</em> port pins the
 * other direction: widening role resolution must not widen port resolution.
 *
 * <p>Each test removes the grant it hung on the shared Everyone role in a
 * finally block; leaving one behind would grant every user in every later test
 * access to that resource.
 */
@QuarkusTest
class RdpGrantEveryoneRoleTest {

    @Inject RdpGrantService grants;
    @PersistenceContext EntityManager em;

    private record Fixture(User user, Site site, Resource resource,
                           ResourcePort rdp, ResourcePort ssh, Role everyone) {}

    @Transactional
    Fixture seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createNew("Everyone " + suffix, "everyone-" + suffix + "@firma.de");
        user.persist();
        // Deliberately no user_roles row: the Everyone role is the only path.
        Site site = Site.createNew("EveryoneSite-" + suffix, "10.63.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "EveryoneTerminal-" + suffix, "10.63.0.5", null, "computer");
        res.persist();
        ResourcePort rdp = ResourcePort.createNew(res.id, 3389, null, "tcp", "RDP", null, null, false, false, "native");
        rdp.persist();
        ResourcePort ssh = ResourcePort.createNew(res.id, 22, null, "tcp", "SSH", null, null, false, false, "native");
        ssh.persist();

        Role everyone = Role.find("autoAll", true).firstResult();
        assertThat(everyone).as("RoleBootstrap must have created the auto_all role").isNotNull();
        return new Fixture(user, site, res, rdp, ssh, everyone);
    }

    @Transactional
    void dropRoleGrants(String roleId, String resourceId) {
        em.createNativeQuery("DELETE FROM role_resource_grant_ports WHERE grant_id IN "
                        + "(SELECT id FROM role_resource_grants WHERE role_id = ?1 AND resource_id = ?2)")
                .setParameter(1, roleId).setParameter(2, resourceId).executeUpdate();
        em.createNativeQuery("DELETE FROM role_resource_grants WHERE role_id = ?1 AND resource_id = ?2")
                .setParameter(1, roleId).setParameter(2, resourceId).executeUpdate();
    }

    @Transactional
    void addAllPortsGrant(String roleId, String resourceId) {
        RoleResourceGrant.createNew(roleId, resourceId, true).persist();
    }

    @Transactional
    void addPortGrant(String roleId, String resourceId, String portId) {
        RoleResourceGrant g = RoleResourceGrant.createNew(roleId, resourceId, false);
        g.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, g.id).setParameter(2, portId).executeUpdate();
    }

    @Transactional
    void addTypeGrant(String roleId, String siteId, String type) {
        RoleResourceTypeGrant.createNew(roleId, siteId, type).persist();
    }

    @Transactional
    void dropTypeGrants(String roleId, String siteId) {
        RoleResourceTypeGrant.delete("roleId = ?1 and siteId = ?2", roleId, siteId);
    }

    @Transactional
    void addNetworkGrant(String roleId, String siteId) {
        RoleNetworkGrant.createNew(roleId, siteId).persist();
    }

    @Transactional
    void dropNetworkGrants(String roleId, String siteId) {
        RoleNetworkGrant.delete("roleId = ?1 and siteId = ?2", roleId, siteId);
    }

    @Test
    void allPortsGrantOnEveryoneRole_opensRdp_forUserWithNoExplicitRole() {
        Fixture f = seed();
        assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false))
                .as("no grant yet").isNull();
        addAllPortsGrant(f.everyone().id, f.resource().id);
        try {
            assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false)).isNotNull();
        } finally {
            dropRoleGrants(f.everyone().id, f.resource().id);
        }
    }

    @Test
    void portScopedGrantOnEveryoneRole_opensOnlyThatPort() {
        Fixture f = seed();
        addPortGrant(f.everyone().id, f.resource().id, f.rdp().id);
        try {
            assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false)).isNotNull();
        } finally {
            dropRoleGrants(f.everyone().id, f.resource().id);
        }
    }

    /** Wider role resolution must not widen port resolution. */
    @Test
    void portScopedGrantOnEveryoneRole_forAnotherPort_staysClosed() {
        Fixture f = seed();
        addPortGrant(f.everyone().id, f.resource().id, f.ssh().id);
        try {
            assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false)).isNull();
        } finally {
            dropRoleGrants(f.everyone().id, f.resource().id);
        }
    }

    @Test
    void typeGrantOnEveryoneRole_opensRdp() {
        Fixture f = seed();
        addTypeGrant(f.everyone().id, f.site().id, "computer");
        try {
            assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false)).isNotNull();
        } finally {
            dropTypeGrants(f.everyone().id, f.site().id);
        }
    }

    @Test
    void networkGrantOnEveryoneRole_opensRdp() {
        Fixture f = seed();
        addNetworkGrant(f.everyone().id, f.site().id);
        try {
            assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false)).isNotNull();
        } finally {
            dropNetworkGrants(f.everyone().id, f.site().id);
        }
    }

    /** The type grant must still match on type, not just on site. */
    @Test
    void typeGrantOnEveryoneRole_forAnotherType_staysClosed() {
        Fixture f = seed();
        addTypeGrant(f.everyone().id, f.site().id, "printer");
        try {
            assertThat(grants.resolveTarget(f.rdp().id, f.user().id, false)).isNull();
        } finally {
            dropTypeGrants(f.everyone().id, f.site().id);
        }
    }
}
