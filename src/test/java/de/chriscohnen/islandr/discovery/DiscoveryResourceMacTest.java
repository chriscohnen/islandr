package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** MAC passthrough on import (issue #76) — the scan/import pipeline itself
 *  (not the ARP-read mechanism, covered in ArpCacheTest/HostProbeTest). */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DiscoveryResourceMacTest {

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("id");
    }

    @Test
    void import_carriesMacThroughToTheCreatedResource() {
        String siteId = createSite("disco-mac", "10.96.0.0/29");
        String body = "{\"hosts\":[{\"ip\":\"10.96.0.5\",\"name\":\"pi-1\",\"type\":\"computer\","
                + "\"mac\":\"B8:27:EB:00:11:22\"}]}";

        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(1));

        given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200)
                .body("find { it.ip == '10.96.0.5' }.mac", equalTo("b8:27:eb:00:11:22"))
                .body("find { it.ip == '10.96.0.5' }.vendor", equalTo("Raspberry Pi Foundation"));
    }

    @Test
    void import_withoutMac_leavesItNull() {
        String siteId = createSite("disco-nomac", "10.97.0.0/29");
        String body = "{\"hosts\":[{\"ip\":\"10.97.0.5\",\"name\":\"plain\",\"type\":\"computer\"}]}";

        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(1));

        given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200)
                .body("find { it.ip == '10.97.0.5' }.mac", equalTo(null));
    }
}
