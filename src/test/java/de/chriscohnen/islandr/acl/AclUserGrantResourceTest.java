package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AclUserGrantResourceTest {

    @PersistenceContext EntityManager em;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.delete("name", "Grant User Role");
        User.delete("email", "usergrant-test@example.test");
    }

    private record Seed(String userId, String resourceId, String portId) {}

    // Deliberately creates its own role + membership rather than relying on
    // the shared auto_all "Everyone" role: sibling test classes in this
    // package delete all roles in their own teardown, and RoleBootstrap only
    // reseeds "Everyone" once at application startup — so depending on it
    // here made this test's pass/fail order-dependent on which other test
    // classes ran first in the same @QuarkusTest JVM.
    @Transactional
    Seed seed() {
        User user = User.createNew("Grant User", "usergrant-test@example.test");
        user.persist();
        Role role = Role.createNew("Grant User Role", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site site = Site.createNew("Homeoffice", "10.33.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "NAS", "10.33.0.5", null, "nas");
        res.persist();
        ResourcePort port = ResourcePort.createNew(res.id, 445, null, "tcp", "SMB", null, null, true, false, "native");
        port.persist();
        return new Seed(user.id, res.id, port.id);
    }

    @Test
    void apply_allPorts_createsGrant() {
        Seed s = seed();

        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":true,\"portIds\":[]}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200)
                .body("changed", is(1));

        given().when().get("/api/v1/acl/my-resources?userId=" + s.userId())
                .then().statusCode(200)
                .body("resources", org.hamcrest.Matchers.hasSize(1))
                .body("resources[0].name", is("NAS"));
    }

    @Test
    void apply_limitedThenEmpty_removesGrant() {
        Seed s = seed();

        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":false,\"portIds\":[\"" + s.portId() + "\"]}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200).body("changed", is(1));

        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":false,\"portIds\":[]}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200).body("changed", is(1));

        given().when().get("/api/v1/acl/my-resources?userId=" + s.userId())
                .then().statusCode(200)
                .body("resources", org.hamcrest.Matchers.hasSize(0));
    }

    @Test
    void apply_foreignPortId_rejected() {
        Seed s = seed();

        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":false,\"portIds\":[\"not-a-real-port-id\"]}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(400);
    }

    // -- issue #70: ad-hoc temporary grant (validUntil) -----------------

    @Test
    void apply_withValidUntil_persistsAndListsExpiry() {
        Seed s = seed();
        String validUntil = "2099-01-01T00:00:00Z";

        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":true,\"portIds\":[],\"validUntil\":\"" + validUntil + "\"}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200).body("changed", is(1));

        given().when().get("/api/v1/acl/user-grants")
                .then().statusCode(200)
                .body("find { it.userId == '" + s.userId() + "' }.validUntil", is(validUntil));
    }

    @Test
    void apply_changingOnlyValidUntil_countsAsChanged() {
        Seed s = seed();
        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":true,\"portIds\":[]}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200).body("changed", is(1));

        // Same allPorts/portIds, only validUntil added — must still be a real change.
        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":true,\"portIds\":[],\"validUntil\":\"2099-06-01T00:00:00Z\"}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200).body("changed", is(1));
    }

    @Test
    void apply_withoutValidUntil_isPermanent() {
        Seed s = seed();
        given().contentType("application/json")
                .body("{\"userId\":\"" + s.userId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":true,\"portIds\":[]}")
                .when().put("/api/v1/acl/user-grants")
                .then().statusCode(200);

        given().when().get("/api/v1/acl/user-grants")
                .then().statusCode(200)
                .body("find { it.userId == '" + s.userId() + "' }.validUntil", is(org.hamcrest.Matchers.nullValue()));
    }
}
