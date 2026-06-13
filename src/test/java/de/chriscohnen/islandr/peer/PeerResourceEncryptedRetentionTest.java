package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Exercises the {@code encrypted} private-key retention mode — keys are stored
 * AES-256-GCM encrypted in the DB but returned as plaintext in API responses.
 *
 * <p>The %test profile supplies a fixed zero-key via {@code islandr.encryption.key}
 * so the EncryptionService is configured. See application.properties.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerResourceEncryptedRetentionTest {

    @BeforeEach
    void switchToEncryptedRetention() {
        setRetention("encrypted");
    }

    @AfterEach
    void resetRetention() {
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
                        { "name": "Lena", "email": "lena-%s@example.com" }
                        """.formatted(java.util.UUID.randomUUID()))
                .when().post("/api/v1/users")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void create_returnsPlaintextPrivateKey() {
        String userId = createUser();
        // The response privateKey must be the raw WireGuard key (44 chars base64),
        // NOT the "enc$..." stored form.
        given().contentType("application/json")
                .body("""
                        { "name": "phone", "assignedIp": "10.8.0.30" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("privateKey", notNullValue())
                .body("privateKey", not(startsWith("enc$")))
                .body("qrPngBase64", notNullValue());
    }

    @Test
    void reshow_returnsPlaintextKeyAndConf() {
        String userId = createUser();
        var created = given().contentType("application/json")
                .body("""
                        { "name": "laptop", "assignedIp": "10.8.0.31" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().response();

        String peerId = created.path("peer.id");
        String originalPriv = created.path("privateKey");

        // Re-display: key must be decrypted and match the original plaintext
        String reshownKey = given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("privateKey", notNullValue())
                .body("privateKey", not(startsWith("enc$")))
                .body("qrPngBase64", notNullValue())
                .extract().path("privateKey");

        assertThat(reshownKey).isEqualTo(originalPriv);
    }
}
