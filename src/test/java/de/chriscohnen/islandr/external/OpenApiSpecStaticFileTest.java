package de.chriscohnen.islandr.external;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @Test
    void isServedAsStaticFile() {
        given().when().get("/api/openapi.yml")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Islandr External API"));
    }

    /** A bare "openapi.yml" lands in ~/Downloads indistinguishable from any
     *  other project's spec — the download must be named after the app and
     *  its exact version (static {@code quarkus.http.filter} config, not
     *  code — see application.properties). */
    @Test
    void downloadFilename_namesTheAppAndItsVersion() {
        given().when().get("/api/openapi.yml")
                .then().statusCode(200)
                .header("Content-Disposition", "attachment; filename=\"islandr-" + appVersion + ".yml\"");
    }
}
