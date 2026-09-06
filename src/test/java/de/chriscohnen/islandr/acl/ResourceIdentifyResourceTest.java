package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

/** Integration test for the on-demand identify action (issue #76). The test
 *  suite runs in mock discovery mode by default (%test.islandr.discovery.mode),
 *  so this exercises the mock-mode branch — deterministic, no real network,
 *  same "never touch the network in tests" rule discovery's own scan follows. */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class ResourceIdentifyResourceTest {

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("id");
    }

    @Test
    void identify_returnsNullFields_inMockMode() {
        String siteId = createSite("identify-test", "10.99.0.0/29");
        String resourceId = given().contentType("application/json")
                .body("{\"name\":\"ghost\",\"ip\":\"10.99.0.5\",\"type\":\"computer\"}")
                .when().post("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(201).extract().path("id");

        given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/identify")
                .then().statusCode(200)
                .body("hostname", nullValue())
                .body("mac", nullValue())
                .body("vendor", nullValue());
    }

    @Test
    void identify_nonexistentResource_returns404() {
        given().contentType("application/json").when().post("/api/v1/resources/does-not-exist/identify")
                .then().statusCode(404);
    }
}
