package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Exercises the {@code plaintext} private-key retention mode — PiVPN-style
 * re-display of the .conf and QR after the original create response was lost.
 *
 * <p>Retention mode now lives in the {@code settings} table (see ADR-0008),
 * so this test switches it via the real {@code PUT /api/v1/settings} endpoint
 * rather than via a Quarkus test profile. The Flyway-seeded row carries
 * {@code retention=never}; each test sets it to {@code plaintext}.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerResourcePlaintextRetentionTest {

    @BeforeEach
    void switchToPlaintextRetention() {
        setRetention("plaintext");
    }

    @AfterEach
    void resetRetention() {
        // The settings row is shared across the suite. Tests that follow this
        // class would otherwise see retention=plaintext (e.g. PeerResourceTest's
        // getConf_returns404InNeverRetentionMode would fail).
        setRetention("never");
    }

    private static void setRetention(String mode) {
        var current = given().when().get("/api/v1/settings").then().statusCode(200).extract().response();
        String body = """
                {
                  "wgSubnet": "%s",
                  "wgServerPublicKey": "%s",
                  "wgServerEndpoint": "%s",
                  "wgClientAllowedIps": "%s",
                  "wgClientDns": %s,
                  "privateKeyRetention": "%s"
                }
                """.formatted(
                current.path("wgSubnet"),
                current.path("wgServerPublicKey"),
                current.path("wgServerEndpoint"),
                current.path("wgClientAllowedIps"),
                current.path("wgClientDns") == null ? "null" : "\"" + current.path("wgClientDns") + "\"",
                mode);
        given().contentType("application/json").body(body)
                .when().put("/api/v1/settings")
                .then().statusCode(200);
    }

    private String createUser() {
        return given().contentType("application/json")
                .body("""
                        { "name": "Felix", "email": "felix-%s@example.com" }
                        """.formatted(java.util.UUID.randomUUID()))
                .when().post("/api/v1/users")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void reshow_returnsSameConfAndPrivateKeyAsCreate() {
        String userId = createUser();
        var created = given().contentType("application/json")
                .body("""
                        { "name": "macbook", "assignedIp": "10.8.0.20" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().response();

        String peerId = created.path("peer.id");
        String originalPriv = created.path("privateKey");
        String originalConf = created.path("conf");

        // re-fetch later (e.g. user lost the QR)
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("peer.id", containsString(peerId))
                .body("privateKey", containsString(originalPriv))
                .body("conf", containsString(originalConf))
                .body("qrPngBase64", notNullValue());
    }

    @Test
    void rotateKey_persistsNewPrivateKeyUnderPlaintextRetention() {
        String userId = createUser();
        var created = given().contentType("application/json")
                .body("""
                        { "name": "rotate-plaintext", "assignedIp": "10.8.0.21" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().response();

        String peerId = created.path("peer.id");
        String originalPriv = created.path("privateKey");

        String rotatedPriv = given().contentType("application/json").when().post("/api/v1/peers/" + peerId + "/rotate-key")
                .then().statusCode(200)
                .body("privateKey", notNullValue())
                .extract().path("privateKey");

        // Rotated key must differ from the original one.
        org.junit.jupiter.api.Assertions.assertNotEquals(originalPriv, rotatedPriv);

        // Reshow must serve the *rotated* key, not the original — proves it was
        // actually persisted under plaintext retention, not just returned once.
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("privateKey", containsString(rotatedPriv));
    }
}
