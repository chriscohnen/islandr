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

    /** session-cookie-secure: over plain HTTP the cookie must not be Secure (the browser would drop it),
     *  and a forged X-Forwarded-Proto from an address nobody named must not change that. */
    @Test
    void login_overPlainHttp_cookieIsNotSecure_evenWithAForgedForwardedProto() {
        Response plain = given().contentType("application/json")
                .body("{ \"username\": \"admin\", \"password\": \"test-admin-pw\" }")
                .when().post("/api/v1/auth/login");
        Response forged = given().contentType("application/json").header("X-Forwarded-Proto", "https")
                .body("{ \"username\": \"admin\", \"password\": \"test-admin-pw\" }")
                .when().post("/api/v1/auth/login");

        assertThat(plain.getDetailedCookie(SessionFilter.COOKIE_NAME).isSecured()).isFalse();
        assertThat(forged.getDetailedCookie(SessionFilter.COOKIE_NAME).isSecured()).isFalse();
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
