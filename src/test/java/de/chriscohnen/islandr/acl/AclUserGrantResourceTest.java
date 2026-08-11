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
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        User.delete("email", "usergrant-test@example.test");
    }

    private record Seed(String userId, String resourceId, String portId) {}

    @Transactional
    Seed seed() {
        User user = User.createNew("Grant User", "usergrant-test@example.test");
        user.persist();
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
}
