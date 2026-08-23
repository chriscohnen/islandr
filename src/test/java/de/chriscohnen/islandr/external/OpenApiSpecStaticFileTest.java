package de.chriscohnen.islandr.external;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * The hand-written OpenAPI spec (ADR-0026) is served as a plain static file
 * — {@code src/main/resources/META-INF/resources/api/openapi.yml}, the same
 * mechanism that already serves the SPA's own index.html/css/js, not a
 * runtime-generated document. Reachable regardless of
 * {@code Settings.externalApiEnabled} (it's documentation, not a facade
 * call) and without any authentication (same as index.html itself).
 */
@QuarkusTest
class OpenApiSpecStaticFileTest {

    @Test
    void isServedAsStaticFile() {
        given().when().get("/api/openapi.yml")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Islandr External API"));
    }
}
