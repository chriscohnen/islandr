package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests PUT/DELETE /api/v1/settings/tls (ADR-0015, issue #22). Ordered because the
 * settings row is shared across the suite (same convention as SettingsResourceTest) —
 * the managed-mode tests build on each other, then reset() at the end restores "none"
 * so later test classes see the default dummy-cert state.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(AdminSessionExtension.class)
class SettingsTlsTest {

    private static String fixture(String name) {
        try (InputStream in = SettingsTlsTest.class.getResourceAsStream("/tls-fixtures/" + name)) {
            if (in == null) throw new IllegalStateException("test fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(1)
    void get_defaultTlsModeIsNone() {
        given().when().get("/api/v1/settings")
                .then().statusCode(200)
                .body("tlsMode", equalTo("none"))
                .body("tlsCertExpiresAt", nullValue());
    }

    @Test
    @Order(2)
    void updateTls_rejectsMalformedPem() {
        given().contentType("application/json")
                .body(Map.of("certPem", "not a certificate", "keyPem", "not a key"))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(400);
    }

    @Test
    @Order(3)
    void updateTls_rejectsExpiredCertificate() {
        given().contentType("application/json")
                .body(Map.of("certPem", fixture("expired-cert.pem"), "keyPem", fixture("expired-key.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(400);
    }

    @Test
    @Order(4)
    void updateTls_rejectsMismatchedCertAndKey() {
        given().contentType("application/json")
                .body(Map.of("certPem", fixture("valid-cert.pem"), "keyPem", fixture("mismatched-key.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(400);
    }

    @Test
    @Order(5)
    void updateTls_validPair_switchesToManagedModeWithExpiry() {
        given().contentType("application/json")
                .body(Map.of("certPem", fixture("valid-cert.pem"), "keyPem", fixture("valid-key.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(200)
                .body("tlsMode", equalTo("managed"))
                .body("tlsCertExpiresAt", notNullValue());
    }

    @Test
    @Order(6)
    void get_reflectsManagedModeAfterUpdate() {
        given().when().get("/api/v1/settings")
                .then().statusCode(200)
                .body("tlsMode", equalTo("managed"))
                .body("tlsCertExpiresAt", notNullValue());
    }

    @Test
    @Order(7)
    void resetTls_returnsToDummyMode() {
        given().when().delete("/api/v1/settings/tls")
                .then().statusCode(200)
                .body("tlsMode", equalTo("none"))
                .body("tlsCertExpiresAt", nullValue());
    }
}
