package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
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
class AclSiteGrantResourceTest {

    @PersistenceContext EntityManager em;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        em.createNativeQuery("DELETE FROM site_resource_grant_ports").executeUpdate();
        SiteResourceGrant.deleteAll();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
    }

    private record Seed(String grantorSiteId, String resourceId, String portId) {}

    @Transactional
    Seed seed() {
        Site grantorSite = Site.createNew("Homeoffice A", "10.33.0.0/16", null);
        grantorSite.persist();
        Site resourceSite = Site.createNew("Office B", "10.44.0.0/16", null);
        resourceSite.persist();
        Resource res = Resource.createNew(resourceSite.id, "NAS", "10.44.0.5", null, "nas");
        res.persist();
        ResourcePort port = ResourcePort.createNew(res.id, 445, null, "tcp", "SMB", null, null, true, false, "native");
        port.persist();
        return new Seed(grantorSite.id, res.id, port.id);
    }

    @Test
    void apply_allPorts_createsGrant() {
        Seed s = seed();

        given().contentType("application/json")
                .body("{\"siteId\":\"" + s.grantorSiteId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":true,\"portIds\":[]}")
                .when().put("/api/v1/acl/site-grants")
                .then().statusCode(200)
                .body("changed", is(1));

        given().when().get("/api/v1/acl/site-grants")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(1))
                .body("[0].resourceId", is(s.resourceId()))
                .body("[0].allPorts", is(true));
    }

    @Test
    void apply_limitedThenEmpty_removesGrant() {
        Seed s = seed();

        given().contentType("application/json")
                .body("{\"siteId\":\"" + s.grantorSiteId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":false,\"portIds\":[\"" + s.portId() + "\"]}")
                .when().put("/api/v1/acl/site-grants")
                .then().statusCode(200).body("changed", is(1));

        given().contentType("application/json")
                .body("{\"siteId\":\"" + s.grantorSiteId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":false,\"portIds\":[]}")
                .when().put("/api/v1/acl/site-grants")
                .then().statusCode(200).body("changed", is(1));

        given().when().get("/api/v1/acl/site-grants")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(0));
    }

    @Test
    void apply_foreignPortId_rejected() {
        Seed s = seed();

        given().contentType("application/json")
                .body("{\"siteId\":\"" + s.grantorSiteId() + "\",\"resourceId\":\"" + s.resourceId()
                        + "\",\"allPorts\":false,\"portIds\":[\"not-a-real-port-id\"]}")
                .when().put("/api/v1/acl/site-grants")
                .then().statusCode(400);
    }
}
