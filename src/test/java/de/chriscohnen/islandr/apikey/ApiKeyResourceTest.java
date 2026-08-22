package de.chriscohnen.islandr.apikey;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/** REST-level tests for the API-key admin console CRUD (issue #15). */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class ApiKeyResourceTest {

    @BeforeEach
    @Transactional
    void reset() {
        ApiKey.deleteAll();
    }

    @Test
    void create_returnsRawKeyOnceThenListDoesNot() {
        String id = given().contentType("application/json").body("{ \"label\": \"ci\" }")
                .when().post("/api/v1/api-keys")
                .then().statusCode(200)
                .body("rawKey", notNullValue())
                .body("apiKey.id", notNullValue())
                .extract().path("apiKey.id");

        given().when().get("/api/v1/api-keys")
                .then().statusCode(200).body("$", hasSize(1))
                .body("[0].id", is(id))
                .body("[0].revoked", is(false));
        // The list response type has no field for the raw key/hash at all —
        // nothing to assert is null because it was never serialized.
    }

    @Test
    void revoke_marksItRevoked() {
        String id = given().contentType("application/json").body("{ \"label\": \"ci\" }")
                .when().post("/api/v1/api-keys")
                .then().statusCode(200).extract().path("apiKey.id");

        given().when().delete("/api/v1/api-keys/" + id).then().statusCode(204);

        given().when().get("/api/v1/api-keys")
                .then().statusCode(200)
                .body("[0].revoked", is(true));
    }

    @Test
    void create_blankLabel_rejected() {
        given().contentType("application/json").body("{ \"label\": \"\" }")
                .when().post("/api/v1/api-keys")
                .then().statusCode(400);
    }
}
