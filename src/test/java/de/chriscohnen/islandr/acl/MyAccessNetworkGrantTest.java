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

/**
 * A network grant ("reach every host in this site") must surface every
 * resource in that site on GET /acl/my-resources, the same way a type-grant
 * already does — this is the self-service-portal-facing half of #78/ADR-0029;
 * RuleBuilder's enforcement half is covered in FirewallTest, the CRUD surface
 * in AclNetworkGrantResourceTest.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class MyAccessNetworkGrantTest {

    @PersistenceContext EntityManager em;
    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        RoleNetworkGrant.deleteAll();
        RoleResourceTypeGrant.deleteAll();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        roleBootstrap.seedEveryoneRole();
        User.delete("email", "erin@example.test");
    }

    private String homeId;

    @Transactional
    String seed() {
        User user = User.createNew("Erin", "erin@example.test");
        user.persist();
        Role role = Role.createNew("NetworkRole", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site home = Site.createNew("HomeNet", "10.95.0.0/16", null);
        home.persist();
        Site office = Site.createNew("OfficeNet", "10.96.0.0/16", null);
        office.persist();
        Resource homeDevice = Resource.createNew(home.id, "HomeNAS", "10.95.0.5", null, "nas");
        homeDevice.persist();
        Resource officeDevice = Resource.createNew(office.id, "OfficeNAS", "10.96.0.5", null, "nas");
        officeDevice.persist();
        ResourcePort.createNew(homeDevice.id, 445, null, "tcp", "SMB", null, null, true, false, "native").persist();
        ResourcePort.createNew(officeDevice.id, 445, null, "tcp", "SMB", null, null, true, false, "native").persist();
        // No concrete RoleResourceGrant/type-grant anywhere — access comes only
        // from the network grant.
        RoleNetworkGrant.createNew(role.id, home.id).persist();
        homeId = home.id;
        return user.id;
    }

    @Test
    void myResources_includesResourceCoveredOnlyByANetworkGrant() {
        String userId = seed();

        given().queryParam("userId", userId)
                .when().get("/api/v1/acl/my-resources")
                .then().statusCode(200)
                .body("resources", hasSize(1))
                .body("resources[0].name", is("HomeNAS"))
                .body("resources[0].siteId", is(homeId));
    }
}
