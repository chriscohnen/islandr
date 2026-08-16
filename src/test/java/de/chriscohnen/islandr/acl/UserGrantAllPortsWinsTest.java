package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class UserGrantAllPortsWinsTest {

    @PersistenceContext EntityManager em;
    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        User.delete("email", "allports-wins@example.test");
        // Reseed the RoleBootstrap "Everyone" auto_all role that Role.deleteAll()
        // just removed (empty, no grants — same as the real seed) so the
        // invariant "exactly one auto_all role always exists" keeps holding
        // for whatever test runs next; its absence otherwise flakes
        // ConfigImportRoundTripTest depending on suite execution order.
        roleBootstrap.seedEveryoneRole();
    }

    @Test
    void directAllPortsGrant_widensBeyondLimitedRoleGrant() {
        String userId = seed();

        given().when().get("/api/v1/acl/my-resources?userId=" + userId)
                .then().statusCode(200)
                .body("resources", hasSize(1))
                .body("resources[0].grantedPorts", hasSize(2)); // both ports, not just the role-granted one
    }

    @Transactional
    String seed() {
        User user = User.createNew("AllPorts User", "allports-wins@example.test");
        user.persist();
        Role role = Role.createNew("Limited", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site site = Site.createNew("HQ", "10.34.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "Fileserver", "10.34.0.5", null, "nas");
        res.persist();
        ResourcePort port1 = ResourcePort.createNew(res.id, 445, null, "tcp", "SMB", null, null, true, false, "native");
        port1.persist();
        ResourcePort port2 = ResourcePort.createNew(res.id, 22, null, "tcp", "SSH", null, null, true, false, "native");
        port2.persist();

        // Role grant limited to just the SMB port.
        RoleResourceGrant roleGrant = RoleResourceGrant.createNew(role.id, res.id, false);
        roleGrant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, roleGrant.id).setParameter(2, port1.id).executeUpdate();

        // Direct user grant, all-ports — must widen the merged result to both ports.
        UserResourceGrant.createNew(user.id, res.id, true).persist();

        return user.id;
    }
}
