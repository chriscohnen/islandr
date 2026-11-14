package de.chriscohnen.islandr.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * A path nothing serves is a 404, not a 500. In 1.3.0-rc.4 every such request
 * failed: the response filters (HSTS) asked {@code ClientAddress} whether the
 * request came over HTTPS, which read the trusted-proxies setting from the
 * database — on the Vert.x event loop, where Quarkus refuses blocking calls.
 * REST endpoints and existing static files run elsewhere and were unaffected,
 * which is why nothing else noticed.
 */
@QuarkusTest
class UnknownPathTest {

    @Test
    void unknownStaticPath_is404() {
        given().when().get("/nope.txt").then().statusCode(404);
    }

    @Test
    void unknownApiPath_is404() {
        given().when().get("/api/v1/does-not-exist").then().statusCode(404);
    }

    @Test
    void unknownPath_viaClaimedProxyHeader_is404_too() {
        given().header("X-Forwarded-Proto", "https").header("X-Forwarded-For", "203.0.113.9")
                .when().get("/nope.txt").then().statusCode(404);
    }
}
