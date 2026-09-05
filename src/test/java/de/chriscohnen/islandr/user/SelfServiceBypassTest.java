package de.chriscohnen.islandr.user;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * The bypass issue #53 is named after, exercised over HTTP rather than against
 * the service objects: a user whose access window has closed must not be able
 * to enrol a fresh, unlimited peer from the self-service portal.
 */
@QuarkusTest
class SelfServiceBypassTest {

    private static final String PASSWORD = "bypass-test-pw-123";

    private record Account(String id, String email) {}

    private Account newUser(Instant validUntil) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "bypass-" + suffix + "@example.test";
        return QuarkusTransaction.requiringNew().call(() -> {
            User u = User.createNew("Bypass " + suffix, email);
            u.validUntil = validUntil;
            u.persist();
            return new Account(u.id, email);
        });
    }

    private void setPassword(String userId) {
        String adminCookie = adminCookie();
        given().cookie("islandr_session", adminCookie).contentType(ContentType.JSON)
                .body(Map.of("password", PASSWORD))
                .when().put("/api/v1/users/" + userId + "/password")
                .then().statusCode(200);
    }

    private String adminCookie() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "test-admin-pw"))
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().cookie("islandr_session");
    }

    @Test
    void aUserInsideTheirWindowCanStillLogIn() {
        Account a = newUser(Instant.now().plusSeconds(3600));
        setPassword(a.id());

        given().contentType(ContentType.JSON)
                .body(Map.of("username", a.email(), "password", PASSWORD))
                .when().post("/api/v1/auth/login")
                .then().statusCode(200);
    }

    /** Blocking the login is the first half of closing the bypass. */
    @Test
    void anExpiredUserCannotLogIn() {
        Account a = newUser(Instant.now().minusSeconds(60));
        setPassword(a.id());

        given().contentType(ContentType.JSON)
                .body(Map.of("username", a.email(), "password", PASSWORD))
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);
    }

    /**
     * The second half, and the one that actually mattered: a session issued
     * while the window was open must stop working when it closes. Without
     * this, anyone already signed in kept self-service peer creation for the
     * rest of the session's lifetime.
     */
    @Test
    void aSessionDoesNotOutliveTheAccessWindowItWasIssuedFor() {
        Account a = newUser(Instant.now().plusSeconds(3600));
        setPassword(a.id());

        String cookie = given().contentType(ContentType.JSON)
                .body(Map.of("username", a.email(), "password", PASSWORD))
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().cookie("islandr_session");

        // The session works while the window is open...
        given().cookie("islandr_session", cookie)
                .when().get("/api/v1/users/me")
                .then().statusCode(200);

        // ...and stops the moment it closes, without waiting for a re-login.
        QuarkusTransaction.requiringNew().run(() -> {
            User u = User.findById(a.id());
            u.validUntil = Instant.now().minusSeconds(1);
        });

        given().cookie("islandr_session", cookie)
                .when().get("/api/v1/users/me")
                .then().statusCode(401);
    }

    @Test
    void anExpiredUserCannotCreateASelfServicePeer() {
        Account a = newUser(Instant.now().plusSeconds(3600));
        setPassword(a.id());

        String cookie = given().contentType(ContentType.JSON)
                .body(Map.of("username", a.email(), "password", PASSWORD))
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().cookie("islandr_session");

        QuarkusTransaction.requiringNew().run(() -> {
            User u = User.findById(a.id());
            u.validUntil = Instant.now().minusSeconds(1);
        });

        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "sneaky-device", "deviceType", "laptop"))
                .when().post("/api/v1/peers/mine")
                .then()
                .statusCode(anyOf401or403());
    }

    /**
     * SessionFilter rejects first (401); if that gate were ever removed the
     * endpoint's own check still refuses (403). Either is a pass — what must
     * not happen is a 201.
     */
    private static org.hamcrest.Matcher<Integer> anyOf401or403() {
        return org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403));
    }

    @Test
    void aDisabledUserCannotLogInEither_unchangedBehaviour() {
        Account a = newUser(null);
        setPassword(a.id());
        QuarkusTransaction.requiringNew().run(() -> {
            User u = User.findById(a.id());
            u.enabled = false;
        });

        given().contentType(ContentType.JSON)
                .body(Map.of("username", a.email(), "password", PASSWORD))
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);
    }
}
