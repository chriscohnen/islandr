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
 * Authorization core for the IronRDP proxy (RdpProxyEndpoint), especially the
 * {@code ?as=} admin-preview path: with {@code bypassAcl=false} the target
 * resolves only when the (impersonated) user actually holds a grant, so a preview
 * reflects that user's real access. The local-admin bypass and the admin check
 * are covered too.
 */
@QuarkusTest
class RdpGrantServiceTest {

    @Inject RdpGrantService grants;
    @PersistenceContext EntityManager em;

    private record Fixture(String userWithGrantId, String userNoGrantId, String rdpPortId,
                           String nonRdpPortId, String resourceIp) {}

    @Transactional
    Fixture seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User granted = User.createNew("Granted " + suffix, "granted-" + suffix + "@firma.de");
        granted.persist();
        User ungranted = User.createNew("Ungranted " + suffix, "ungranted-" + suffix + "@firma.de");
        ungranted.persist();

        Role role = Role.createNew("RDPRole-" + suffix, null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, granted.id).setParameter(2, role.id).executeUpdate();

        Site site = Site.createNew("Site-" + suffix, "10.60.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "Terminal-" + suffix, "10.60.0.5", null, "computer");
        res.persist();
        ResourcePort rdp = ResourcePort.createNew(res.id, 3389, null, "tcp", "RDP", null, null, false, false, "native");
        rdp.persist();
        ResourcePort ssh = ResourcePort.createNew(res.id, 22, null, "tcp", "SSH", null, null, false, false, "native");
        ssh.persist();

        // all-ports grant for the role → the RDP port is covered.
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, true);
        grant.persist();

        return new Fixture(granted.id, ungranted.id, rdp.id, ssh.id, res.ip);
    }

    @Test
    void resolveTarget_returnsTarget_whenUserHasGrant() {
        Fixture f = seed();
        RdpGrantService.RdpTarget target = grants.resolveTarget(f.rdpPortId(), f.userWithGrantId(), false);
        assertThat(target).isNotNull();
        assertThat(target.host()).isEqualTo(f.resourceIp());
        assertThat(target.port()).isEqualTo(3389);
    }

    /** The security property behind ?as=: no grant → no target, even without the bypass. */
    @Test
    void resolveTarget_returnsNull_whenUserLacksGrant() {
        Fixture f = seed();
        assertThat(grants.resolveTarget(f.rdpPortId(), f.userNoGrantId(), false)).isNull();
    }

    /** The local-admin (ENV) bypass still lets the admin reach any RDP port directly. */
    @Test
    void resolveTarget_bypassesAcl_forLocalAdmin() {
        Fixture f = seed();
        assertThat(grants.resolveTarget(f.rdpPortId(), f.userNoGrantId(), true)).isNotNull();
    }

    @Test
    void resolveTarget_returnsNull_forNonRdpPort() {
        Fixture f = seed();
        assertThat(grants.resolveTarget(f.nonRdpPortId(), f.userWithGrantId(), true)).isNull();
    }

    @Test
    @Transactional
    void isAdmin_reflectsUserFlag() {
        User admin = User.createNew("Admin " + UUID.randomUUID(), "admin-" + UUID.randomUUID() + "@firma.de");
        admin.isAdmin = true;
        admin.persist();
        User normal = User.createNew("Normal " + UUID.randomUUID(), "normal-" + UUID.randomUUID() + "@firma.de");
        normal.persist();

        assertThat(grants.isAdmin(admin.id)).isTrue();
        assertThat(grants.isAdmin(normal.id)).isFalse();
        assertThat(grants.isAdmin(null)).isFalse();
    }

    @Test
    @Transactional
    void resolveTarget_returnsTarget_whenUserHasDirectGrant_noRoleInvolved() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        User user = User.createNew("Direct " + suffix, "direct-" + suffix + "@firma.de");
        user.persist();
        Site site = Site.createNew("DirectSite-" + suffix, "10.61.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "DirectTerminal-" + suffix, "10.61.0.5", null, "computer");
        res.persist();
        ResourcePort rdp = ResourcePort.createNew(res.id, 3389, null, "tcp", "RDP", null, null, false, false, "native");
        rdp.persist();
        // No role, no user_roles row — direct grant only.
        UserResourceGrant.createNew(user.id, res.id, true).persist();

        RdpGrantService.RdpTarget target = grants.resolveTarget(rdp.id, user.id, false);
        assertThat(target).isNotNull();
        assertThat(target.host()).isEqualTo(res.ip);
        assertThat(target.port()).isEqualTo(3389);
    }
}
