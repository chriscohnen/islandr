package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Shared seed: V5 inserts disabled rows for 'microsoft' and 'google'.
 * Other suites (OidcLoginServiceTest) mutate these singletons, so we reset
 * to the seeded state in {@link #resetSeed} before each test here.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(AdminSessionExtension.class)
class OidcProviderResourceTest {

    @BeforeEach
    @Transactional
    void resetSeed() {
        for (String key : new String[] { "microsoft", "google" }) {
            OidcProvider p = OidcProvider.findById(key);
            if (p == null) continue;
            p.enabled = false;
            p.clientId = null;
            p.clientSecret = null;
            p.tenantId = null;
            p.allowedDomains = null;
            p.updatedAt = Instant.now();
            p.updatedBy = "test:reset";
        }
    }

    @Test @Order(1)
    void list_returnsSeededDisabledProviders() {
        given().when().get("/api/v1/identity/providers")
                .then().statusCode(200)
                .body("$", hasSize(2))
                .body("providerKey", org.hamcrest.Matchers.hasItems("microsoft", "google"))
                .body("find { it.providerKey == 'microsoft' }.enabled", is(false))
                .body("find { it.providerKey == 'google' }.enabled", is(false))
                .body("find { it.providerKey == 'microsoft' }.clientSecretSet", is(false));
    }

    @Test @Order(2)
    void enableMicrosoftWithoutClientId_fails() {
        given().contentType("application/json").body("""
                { "enabled": true }
                """)
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(400);
    }

    @Test @Order(3)
    void updateMicrosoftWithCredentials_succeeds_secretWriteOnly() {
        given().contentType("application/json").body("""
                {
                  "clientId": "ms-app-id-123",
                  "clientSecret": "super-secret-value",
                  "tenantId": "11111111-2222-3333-4444-555555555555",
                  "allowedDomains": "firma.de, EXAMPLE.com"
                }
                """)
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(200)
                .body("providerKey", equalTo("microsoft"))
                .body("clientId", equalTo("ms-app-id-123"))
                .body("clientSecretSet", is(true))
                .body("clientSecret", org.hamcrest.Matchers.nullValue())  // never returned
                .body("tenantId", equalTo("11111111-2222-3333-4444-555555555555"))
                .body("allowedDomains", equalTo("firma.de,example.com"));  // normalised
    }

    @Test @Order(4)
    void enableMicrosoftNowSucceeds() {
        // BeforeEach reset clears credentials, so seed them again before enabling.
        seedMicrosoftCredentials();
        given().contentType("application/json").body("""
                { "enabled": true }
                """)
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(200)
                .body("enabled", is(true))
                .body("clientSecretSet", is(true));
    }

    @Test @Order(5)
    void emptyClientSecret_doesNotClearExisting() {
        seedMicrosoftCredentials();
        given().contentType("application/json").body("""
                { "clientSecret": "" }
                """)
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(200)
                .body("clientSecretSet", is(true));
    }

    private static void seedMicrosoftCredentials() {
        given().contentType("application/json").body("""
                {
                  "clientId": "ms-app-id-123",
                  "clientSecret": "super-secret-value",
                  "tenantId": "11111111-2222-3333-4444-555555555555",
                  "allowedDomains": "firma.de"
                }
                """)
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(200);
    }

    @Test @Order(6)
    void enableGoogleWithoutTenant_succeeds() {
        // Google ignores tenantId — credentials alone are sufficient to enable.
        given().contentType("application/json").body("""
                {
                  "clientId": "google-app-id",
                  "clientSecret": "google-secret",
                  "allowedDomains": "firma.de"
                }
                """)
                .when().put("/api/v1/identity/providers/google")
                .then().statusCode(200);

        given().contentType("application/json").body("""
                { "enabled": true }
                """)
                .when().put("/api/v1/identity/providers/google")
                .then().statusCode(200)
                .body("enabled", is(true));
    }

    @Test @Order(7)
    void unknownProviderKey_returns404() {
        given().contentType("application/json").body("{}")
                .when().put("/api/v1/identity/providers/facebook")
                .then().statusCode(404);
    }

    @Test @Order(8)
    void malformedAllowedDomains_rejected() {
        given().contentType("application/json").body("""
                { "allowedDomains": "not a domain ; nope" }
                """)
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(400);
    }

    /**
     * Issue #81: the Secret ID and the secret Value sit side by side in the
     * Entra portal and only the Value is ever shown again, so pasting the ID
     * is the most common setup mistake. A UUID-shaped secret is refused before
     * anything is stored — no network call needed to know it is wrong.
     */
    @Test @Order(90)
    void microsoft_rejectsAUuidShapedClientSecretAsTheSecretId() {
        given().contentType("application/json")
                .body("{\"clientId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"tenantId\":\"44444444-4444-4444-4444-444444444444\","
                        + "\"clientSecret\":\"55555555-5555-5555-5555-555555555555\"}")
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(400);

        // Nothing was saved — not even the fields that came with it.
        given().when().get("/api/v1/identity/providers/microsoft")
                .then().statusCode(200)
                .body("clientSecretSet", is(false))
                .body("clientId", org.hamcrest.Matchers.nullValue());
    }

    /** A real secret Value is opaque, not a UUID, and still goes through. */
    @Test @Order(91)
    void microsoft_acceptsANonUuidClientSecret() {
        given().contentType("application/json")
                .body("{\"clientId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"tenantId\":\"44444444-4444-4444-4444-444444444444\","
                        + "\"clientSecret\":\"8Xt~Q3.bK2lR-9Vd_uW1yZaB4cE5fG6h\"}")
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(200)
                .body("clientSecretSet", is(true));
    }

    /** The configuration test is Entra-specific; Google has no AADSTS codes. */
    @Test @Order(92)
    void configurationTest_isRefusedForNonMicrosoftProviders() {
        given().when().post("/api/v1/identity/providers/google/test")
                .then().statusCode(400);
    }
}
