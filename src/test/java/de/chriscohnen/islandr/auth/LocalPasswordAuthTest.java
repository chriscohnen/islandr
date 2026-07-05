package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Local users with passwords (design 2026-07-05, F-01a): an admin creates a user
 * and sets a password; the user then logs in with email + password, independent of
 * OIDC. Password material is never echoed back. The admin session for the setup
 * calls comes from {@link AdminSessionExtension}.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class LocalPasswordAuthTest {

    private String createUserWithPassword(String email, String password) {
        String uid = given().contentType("application/json")
                .body("{\"name\":\"Local User\",\"email\":\"" + email + "\"}")
                .when().post("/api/v1/users")
                .then().statusCode(201)
                // password must never be present on the user projection
                .body("passwordHash", nullValue())
                .extract().path("id");
        given().contentType("application/json")
                .body("{\"password\":\"" + password + "\"}")
                .when().put("/api/v1/users/" + uid + "/password")
                .then().statusCode(200)
                .body("passwordHash", nullValue());
        return uid;
    }

    @Test
    void localUser_withPassword_logsInAsNonAdmin() {
        String email = "local-" + UUID.randomUUID() + "@firma.de";
        String uid = createUserWithPassword(email, "bob-secret-pw");

        given().contentType("application/json")
                .body("{\"username\":\"" + email + "\",\"password\":\"bob-secret-pw\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .body("userId", is(uid))
                .body("isAdmin", is(false))
                .body("provider", is("local"));
    }

    @Test
    void localUser_wrongPassword_isRejected() {
        String email = "local-" + UUID.randomUUID() + "@firma.de";
        createUserWithPassword(email, "right-pw-value");

        given().contentType("application/json")
                .body("{\"username\":\"" + email + "\",\"password\":\"wrong-pw-value\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);
    }

    @Test
    void userWithoutPassword_cannotLogInLocally() {
        String email = "nopass-" + UUID.randomUUID() + "@firma.de";
        given().contentType("application/json")
                .body("{\"name\":\"No Pass\",\"email\":\"" + email + "\"}")
                .when().post("/api/v1/users").then().statusCode(201);

        given().contentType("application/json")
                .body("{\"username\":\"" + email + "\",\"password\":\"anything-here\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);
    }
}
