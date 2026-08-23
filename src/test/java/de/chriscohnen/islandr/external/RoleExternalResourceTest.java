package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class RoleExternalResourceTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @Transactional
    void reset() {
        de.chriscohnen.islandr.apikey.ApiKey.deleteAll();
    }

    @Test
    void bearerToken_canListRoles() {
        String rawKey = apiKeys.create("test-key", "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/roles")
                .then().statusCode(200);
    }

    @Test
    void noCredentials_rejected() {
        given().when().get("/api/external/v1/roles").then().statusCode(401);
    }
}
