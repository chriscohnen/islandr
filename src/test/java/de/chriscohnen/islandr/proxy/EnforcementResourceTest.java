package de.chriscohnen.islandr.proxy;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@code GET /api/v1/enforcement/status} (design §7): exposes the
 * enforcement state, timestamps, last error, and the runtime diagnostic
 * (container / socket mode) that the Admin Console banner reads.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EnforcementResourceTest {

    @Inject EnforcementStatus enforcement;

    @BeforeEach
    void reset() {
        enforcement.markActive();
    }

    @Test
    void status_whenActive_reports200AndActiveWithRuntime() {
        given().when().get("/api/v1/enforcement/status")
                .then().statusCode(200)
                .body("status", equalTo("active"))
                .body("lastError", is(org.hamcrest.Matchers.nullValue()))
                // In the test JVM we are neither in a container nor in socket mode.
                .body("runtime.container", equalTo(false))
                .body("runtime.socketMode", equalTo(false));
    }

    @Test
    void status_whenUnavailable_reportsUnavailableWithError() {
        enforcement.markUnavailable("proxy socket absent");

        given().when().get("/api/v1/enforcement/status")
                .then().statusCode(200)
                .body("status", equalTo("unavailable"))
                .body("lastError", equalTo("proxy socket absent"));
    }
}
