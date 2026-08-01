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
                .body("wgPersistentKeepalive", equalTo(25))   // issue #28: seeded default
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
                  "privateKeyRetention": "never",
                  "wgPersistentKeepalive": 30
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(200)
                .body("wgSubnet", equalTo("10.9.0.0/24"))
                .body("setupComplete", is(true))
                .body("wgClientDns", equalTo("10.9.0.1"))
                .body("wgPersistentKeepalive", equalTo(30));   // issue #28: global default round-trips
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

    @Test
    @Order(5)
    void put_roundTripsTunnelSettingsAndComputesPreview() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.9.0.0/24",
                  "wgServerPublicKey": "VGhpc0lzNDRDaGFyc09mUGxhdXNpYmxlQmFzZTY0RGF0YQ==",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.9.0.0/24,10.20.0.0/16",
                  "wgClientDns": "10.9.0.1",
                  "privateKeyRetention": "never",
                  "tunnelMode": "SPLIT",
                  "allowedIpsMode": "AUTO",
                  "splitSupernet": "10.0.0.0/8"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(200)
                .body("tunnelMode", equalTo("SPLIT"))
                .body("allowedIpsMode", equalTo("AUTO"))
                .body("splitSupernet", equalTo("10.0.0.0/8"))
                .body("computedAllowedIpsPreview", equalTo("10.9.0.0/24, 10.0.0.0/8"));
    }

    @Test
    @Order(6)
    void put_defaultsTunnelSettingsWhenOmitted() {
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
                .body("tunnelMode", equalTo("SPLIT"))
                .body("allowedIpsMode", equalTo("MANUAL"));
    }

    @Test
    @Order(7)
    void put_rejectsInvalidTunnelMode() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.8.0.0/24",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.8.0.0/24",
                  "wgClientDns": null,
                  "privateKeyRetention": "never",
                  "tunnelMode": "yolo"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(8)
    void put_rejectsInvalidAllowedIpsMode() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.8.0.0/24",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.8.0.0/24",
                  "wgClientDns": null,
                  "privateKeyRetention": "never",
                  "allowedIpsMode": "yolo"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(9)
    void put_rejectsBlankAllowedIpsInManualMode() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.8.0.0/24",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "",
                  "wgClientDns": null,
                  "privateKeyRetention": "never",
                  "allowedIpsMode": "MANUAL"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(10)
    void allowedIpsPreview_computesFromQueryParams_overridingSavedMode() {
        // Settings row is SPLIT/MANUAL with wgSubnet 10.9.0.0/24 as of @Order(6);
        // this call previews SPLIT/AUTO with a supernet without saving anything.
        given()
                .queryParam("tunnelMode", "SPLIT")
                .queryParam("allowedIpsMode", "AUTO")
                .queryParam("splitSupernet", "10.0.0.0/8")
                .when().get("/api/v1/settings/allowed-ips-preview")
                .then().statusCode(200)
                .body("preview", equalTo("10.9.0.0/24, 10.0.0.0/8"))
                .body("sitesOutsideSupernetCount", equalTo(0));
    }

    @Test
    @Order(11)
    void allowedIpsPreview_fallsBackToSavedValues_whenParamsOmitted() {
        given()
                .when().get("/api/v1/settings/allowed-ips-preview")
                .then().statusCode(200)
                // Saved mode is MANUAL (default since @Order(6)) -> raw wgClientAllowedIps verbatim.
                .body("preview", equalTo("10.9.0.0/24,10.20.0.0/16"));
    }

    @Test
    @Order(12)
    void allowedIpsPreview_manualValueParam_previewsUnsavedManualEdit() {
        given()
                .queryParam("wgClientAllowedIps", "192.168.99.0/24")
                .when().get("/api/v1/settings/allowed-ips-preview")
                .then().statusCode(200)
                .body("preview", equalTo("192.168.99.0/24"));
    }
}
