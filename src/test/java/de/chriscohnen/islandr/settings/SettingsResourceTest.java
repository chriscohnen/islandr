package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * Settings tests run in declared order because the settings row is shared
 * across the suite (one singleton table, no per-test rollback for REST flows).
 * The read-then-write sequence here is intentional.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(AdminSessionExtension.class)
class SettingsResourceTest {

    @Test
    @Order(1)
    void get_returnsFlywaySeededDefaults() {
        given().when().get("/api/v1/settings")
                .then().statusCode(200)
                .body("wgSubnet", equalTo("10.8.0.0/24"))
                .body("wgServerPublicKey", startsWith("PLACEHOLDER"))
                .body("privateKeyRetention", equalTo("never"))
                .body("setupComplete", is(false));
    }

    @Test
    @Order(2)
    void put_updatesAllFieldsAndFlipsSetupComplete() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.9.0.0/24",
                  "wgServerPublicKey": "VGhpc0lzNDRDaGFyc09mUGxhdXNpYmxlQmFzZTY0RGF0YQ==",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.9.0.0/24,10.20.0.0/16",
                  "wgClientDns": "10.9.0.1",
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(200)
                .body("wgSubnet", equalTo("10.9.0.0/24"))
                .body("setupComplete", is(true))
                .body("wgClientDns", equalTo("10.9.0.1"));
    }

    @Test
    @Order(3)
    void put_rejectsInvalidRetentionMode() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.8.0.0/24",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.8.0.0/24",
                  "wgClientDns": null,
                  "privateKeyRetention": "yolo"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(4)
    void put_rejectsMalformedSubnet() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "not-a-cidr",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.8.0.0/24",
                  "wgClientDns": null,
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }
}
