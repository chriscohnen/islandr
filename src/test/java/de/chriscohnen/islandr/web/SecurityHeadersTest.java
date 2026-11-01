package de.chriscohnen.islandr.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** security-headers: every answer carries the fixed headers; HSTS never over plain HTTP. */
@QuarkusTest
class SecurityHeadersTest {

    @Test
    void apiAnswer_carriesTheFixedHeaders_andNoHsts() {
        given().when().get("/api/v1/auth/me")
                .then()
                .header("X-Frame-Options", equalTo("DENY"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Referrer-Policy", equalTo("same-origin"))
                .header("Permissions-Policy", equalTo("camera=(), microphone=(), geolocation=()"))
                .header("Strict-Transport-Security", nullValue());
    }

    @Test
    void staticFile_carriesTheFixedHeaders() {
        given().when().get("/index.html")
                .then().statusCode(200)
                .header("X-Frame-Options", equalTo("DENY"))
                .header("X-Content-Type-Options", equalTo("nosniff"));
    }

    @Test
    void forgedForwardedProto_doesNotEarnHsts() {
        given().header("X-Forwarded-Proto", "https").when().get("/api/v1/auth/me")
                .then().header("Strict-Transport-Security", nullValue());
    }
}
