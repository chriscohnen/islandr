package de.chriscohnen.islandr.external;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * {@code llms.txt} (llms-txt-and-handoff) — same serving mechanism as
 * {@link OpenApiSpecStaticFileTest}'s {@code openapi.yml}: a hand-written
 * static file under {@code META-INF/resources/}, not generated at runtime.
 * At the conventional root path ({@code /llms.txt}), not nested under
 * {@code /api/} like the OpenAPI spec — that's where the llms.txt
 * convention expects it, same idea as {@code /robots.txt}.
 */
@QuarkusTest
class LlmsTxtStaticFileTest {

    @Test
    void isServedAsStaticFile() {
        given().when().get("/llms.txt")
                .then().statusCode(200)
                .body(containsString("Islandr External API"))
                .body(containsString("/api/external/v1"))
                .body(containsString("openapi.yml"));
    }
}
