package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/** REST-level tests for the generic-OIDC-provider admin API (issue #69). */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class OidcCustomProviderResourceTest {

    private static final String ISSUER = "https://rest-test.example.com";

    @Inject FakeHttpFetcher http;

    @BeforeEach
    void resetHttpAndData() {
        http.reset();
        deleteAll();
        http.stubJson(ISSUER + "/.well-known/openid-configuration", 200, """
            {"issuer":"%s",
             "authorization_endpoint":"%s/authorize",
             "token_endpoint":"%s/token",
             "jwks_uri":"%s/jwks"}
            """.formatted(ISSUER, ISSUER, ISSUER, ISSUER));
    }

    // Unauthenticated rejection is covered once, for every admin endpoint,
    // in AuthorizationMatrixTest — this class stays committed to
    // AdminSessionExtension's admin session for every test.

    @Test
    void create_thenList_thenGet() {
        String id = createViaRest();

        given().when().get("/api/v1/identity/custom-providers")
                .then().statusCode(200).body("$", hasSize(1))
                .body("[0].id", is(id))
                .body("[0].discovered", is(true))
                .body("[0].enabled", is(false));

        given().when().get("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(200)
                .body("displayName", is("Rest Test IdP"))
                .body("clientSecretSet", is(true))
                .body("clientSecret", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void enable_thenDisableAgain() {
        String id = createViaRest();

        given().contentType("application/json").body("{ \"enabled\": true }")
                .when().put("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(200).body("enabled", is(true));

        given().contentType("application/json").body("{ \"enabled\": false }")
                .when().put("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(200).body("enabled", is(false));
    }

    @Test
    void rediscover_refreshesEndpoints() {
        String id = createViaRest();

        given().contentType("application/json")
                .when().post("/api/v1/identity/custom-providers/" + id + "/rediscover")
                .then().statusCode(200)
                .body("discovered", is(true));
    }

    @Test
    void delete_whileDisabled_succeeds() {
        String id = createViaRest();
        given().when().delete("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(204);

        given().when().get("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(404);
    }

    @Test
    void delete_whileEnabled_rejected() {
        String id = createViaRest();
        given().contentType("application/json").body("{ \"enabled\": true }")
                .when().put("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(200);

        given().when().delete("/api/v1/identity/custom-providers/" + id)
                .then().statusCode(400);
    }

    @Test
    void create_missingDisplayName_rejected() {
        given().contentType("application/json").body("""
                { "issuerUrl": "%s" }
                """.formatted(ISSUER))
                .when().post("/api/v1/identity/custom-providers")
                .then().statusCode(400);
    }

    @Test
    void create_discoveryFailure_returns400() {
        given().contentType("application/json").body("""
                { "issuerUrl": "https://unstubbed.example.com", "displayName": "Broken" }
                """)
                .when().post("/api/v1/identity/custom-providers")
                .then().statusCode(400);
    }

    // -- helpers --------------------------------------------------------

    private String createViaRest() {
        return given().contentType("application/json").body("""
                {
                  "issuerUrl": "%s",
                  "displayName": "Rest Test IdP",
                  "clientId": "rest-client",
                  "clientSecret": "rest-secret",
                  "allowedDomains": "firma.de"
                }
                """.formatted(ISSUER))
                .when().post("/api/v1/identity/custom-providers")
                .then().statusCode(200).body("id", notNullValue())
                .extract().path("id");
    }

    @jakarta.transaction.Transactional
    void deleteAll() {
        for (OidcCustomProvider p : OidcCustomProvider.<OidcCustomProvider>listAll()) {
            p.enabled = false;
            p.delete();
        }
    }
}
