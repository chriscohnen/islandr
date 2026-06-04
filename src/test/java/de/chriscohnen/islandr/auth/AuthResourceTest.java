package de.chriscohnen.islandr.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local admin sessions only. OIDC flow tests live alongside the OIDC code in step 4.
 * Test profile sets ISLANDR_ADMIN_USER=admin / ISLANDR_ADMIN_PASSWORD=test-admin-pw
 * via application.properties (%test prefix).
 */
@QuarkusTest
class AuthResourceTest {

    @Test
    void me_withoutCookie_returns401() {
        given().when().get("/api/v1/auth/me").then().statusCode(401);
    }

    @Test
    void login_wrongPassword_returns401() {
        given().contentType("application/json").body("""
                { "username": "admin", "password": "wrong" }
                """)
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);
    }

    @Test
    void login_wrongUser_returns401() {
        given().contentType("application/json").body("""
                { "username": "root", "password": "test-admin-pw" }
                """)
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);
    }

    @Test
    void login_correctCredentials_setsCookie_meReturnsPrincipal_logoutClears() {
        Response loginResp = given().contentType("application/json").body("""
                { "username": "admin", "password": "test-admin-pw" }
                """)
                .when().post("/api/v1/auth/login");

        loginResp.then().statusCode(200)
                .body("principal", org.hamcrest.Matchers.equalTo("admin"))
                .body("provider", org.hamcrest.Matchers.equalTo("local"));

        Cookie sessionCookie = loginResp.getDetailedCookie(SessionFilter.COOKIE_NAME);
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie.getValue()).isNotBlank();
        assertThat(sessionCookie.isHttpOnly()).isTrue();

        // /me with the cookie returns the principal.
        given().cookie(SessionFilter.COOKIE_NAME, sessionCookie.getValue())
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .body("principal", org.hamcrest.Matchers.equalTo("admin"));

        // Logout revokes; subsequent /me with same cookie value is 401.
        given().cookie(SessionFilter.COOKIE_NAME, sessionCookie.getValue())
                .when().post("/api/v1/auth/logout")
                .then().statusCode(204);

        given().cookie(SessionFilter.COOKIE_NAME, sessionCookie.getValue())
                .when().get("/api/v1/auth/me")
                .then().statusCode(401);
    }

    @Test
    void login_emptyBody_returns400() {
        given().contentType("application/json").body("{}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(400);
    }
}
