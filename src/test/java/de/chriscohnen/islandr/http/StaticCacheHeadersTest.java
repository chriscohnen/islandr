package de.chriscohnen.islandr.http;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Front-end assets must be revalidated on every load so a new release is picked
 * up without a manual hard-refresh. The app has no bundler (Vue from CDN,
 * self-hosted JS), so there are no content-hashed filenames — instead the server
 * sends {@code Cache-Control: no-cache} for the app code and the SPA entry point,
 * and the static handler's ETag turns unchanged loads into cheap 304s.
 */
@QuarkusTest
class StaticCacheHeadersTest {

    @Test
    void appJs_isServedWithRevalidation() {
        given().when().get("/js/app.js")
                .then().statusCode(200)
                .header("Cache-Control", containsString("no-cache"));
    }

    @Test
    void viewModule_isServedWithRevalidation() {
        given().when().get("/js/views/SettingsView.js")
                .then().statusCode(200)
                .header("Cache-Control", containsString("no-cache"));
    }

    @Test
    void indexHtml_isServedWithRevalidation() {
        given().when().get("/index.html")
                .then().statusCode(200)
                .header("Cache-Control", containsString("no-cache"));
    }
}
