package de.chriscohnen.islandr.auth;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that logs in as the test-profile local admin and pins
 * the resulting session cookie as the RestAssured default for the duration
 * of one test, then clears it afterwards. Tests that need to assert
 * 401-unauthenticated behaviour can simply not annotate themselves with
 * this extension (or send their own cookie explicitly).
 *
 * Most existing resource tests pre-date SessionFilter and assume open
 * endpoints — wiring this extension is the smallest surface-area change
 * to make them pass under the new admin-only resources.
 */
public final class AdminSessionExtension implements BeforeEachCallback, AfterEachCallback {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "test-admin-pw";  // matches %test profile

    @Override
    public void beforeEach(ExtensionContext context) {
        String cookieValue = login();
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie(SessionFilter.COOKIE_NAME, cookieValue)
                .build();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        RestAssured.requestSpecification = null;
    }

    private static String login() {
        var resp = io.restassured.RestAssured.given()
                .contentType("application/json")
                .body("{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}")
                .when()
                .post("/api/v1/auth/login");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(
                    "test admin login failed (status=" + resp.statusCode() +
                    ") — check %test.islandr.admin.* in application.properties");
        }
        return resp.getDetailedCookie(SessionFilter.COOKIE_NAME).getValue();
    }
}
