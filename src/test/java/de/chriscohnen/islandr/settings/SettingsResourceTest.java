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

    // settings-reject-unusable-wg-subnet (loop/TASK.md): a /31 or /32 is a
    // syntactically valid CIDR (passes @ValidCidr) but IpSubnet.assignableHostIps()
    // is empty for it — PeerService.suggestNextIp() would only discover that
    // at the first peer, days later, with a misleading "no free IP" message.
    // Reject it at save time instead, where the admin can still fix it.

    @Test
    @Order(13)
    void put_rejectsASlash31WgSubnet_tooNarrowForAnyHost() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.8.0.0/31",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.8.0.0/31",
                  "wgClientDns": null,
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(14)
    void put_rejectsASlash32WgSubnet_tooNarrowForAnyHost() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.8.0.0/32",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.8.0.0/32",
                  "wgClientDns": null,
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(15)
    void put_rejectsASlash127WgSubnet6_tooNarrowForAnyHost() {
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.9.0.0/24",
                  "wgSubnet6": "fd11::/127",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.9.0.0/24",
                  "wgClientDns": null,
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(400);
    }

    @Test
    @Order(16)
    void put_acceptsASlash30WgSubnet_narrowestStillUsable() {
        // /30 IPv4 still has exactly one assignable host slot (network,
        // server and broadcast all reserved out of its four addresses —
        // see IpSubnetTest#assignableHostIps_slash30_hasExactlyOneSlot) —
        // the actual boundary, not an arbitrary round number, worth
        // pinning down so the constraint can't silently drift to
        // rejecting a subnet that still works.
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.9.1.0/30",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.9.1.0/30",
                  "wgClientDns": null,
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(200)
                .body("wgSubnet", equalTo("10.9.1.0/30"));

        // The settings row is a suite-wide singleton (see the class javadoc) —
        // leaving a one-address subnet in place would starve every other test
        // class's IP allocation that runs after this one. Restore a roomy
        // subnet before handing the row back.
        given().contentType("application/json").body("""
                {
                  "wgSubnet": "10.9.0.0/24",
                  "wgServerPublicKey": "key",
                  "wgServerEndpoint": "vpn.example.com:51820",
                  "wgClientAllowedIps": "10.9.0.0/24",
                  "wgClientDns": null,
                  "privateKeyRetention": "never"
                }
                """)
                .when().put("/api/v1/settings")
                .then().statusCode(200);
    }
}
