package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class UserExternalResourceTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @Transactional
    void reset() {
        de.chriscohnen.islandr.apikey.ApiKey.deleteAll();
    }

    @Test
    void bearerToken_canListUsers() {
        String rawKey = apiKeys.create("test-key", "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/users")
                .then().statusCode(200);
    }

    @Test
    void noCredentials_rejected() {
        given().when().get("/api/external/v1/users").then().statusCode(401);
    }
}
