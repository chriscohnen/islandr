package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the auth matrix for the admin-only and self-service endpoints
 * added with V6. Runs without {@link AdminSessionExtension} on purpose —
 * each test crafts the session it needs (or sends none).
 *
 * Matrix:
 *   anonymous + admin endpoint         → 401
 *   non-admin org user + admin endpoint → 403
 *   admin + admin endpoint              → 200
 *   anonymous + /peers/mine             → 401
 *   local admin + /peers/mine           → 403 (no users row to scope to)
 *   org user + /peers/mine              → 200
 */
@QuarkusTest
class AuthorizationMatrixTest {

    @Inject SessionService sessions;

    private String orgUserId;
    private String adminOrgUserId;

    @BeforeEach
    @Transactional
    void seed() {
        orgUserId = persistUser("Olivia OrgUser", "olivia-" + UUID.randomUUID() + "@firma.de", false);
        adminOrgUserId = persistUser("Adina OrgAdmin", "adina-" + UUID.randomUUID() + "@firma.de", true);
    }

    @Test
    void anonymous_cannotHitAdminEndpoints() {
        given().when().get("/api/v1/users").then().statusCode(401);
        given().when().get("/api/v1/peers").then().statusCode(401);
        given().when().get("/api/v1/settings").then().statusCode(401);
        given().when().get("/api/v1/identity/providers").then().statusCode(401);
        given().when().get("/api/v1/audit").then().statusCode(401);
        given().when().get("/api/v1/dashboard").then().statusCode(401);
        given().when().get("/api/v1/firewall").then().statusCode(401);
    }

    @Test
    void nonAdminOrgUser_isForbiddenFromAdminEndpoints() {
        String cookie = sessionCookieFor(orgUserId, false);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/users").then().statusCode(403);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers").then().statusCode(403);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/settings").then().statusCode(403);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/audit").then().statusCode(403);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/dashboard").then().statusCode(403);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/firewall").then().statusCode(403);
    }

    @Test
    void adminOrgUser_canHitAdminEndpoints() {
        String cookie = sessionCookieFor(adminOrgUserId, false);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/users").then().statusCode(200);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers").then().statusCode(200);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/settings").then().statusCode(200);
    }

    @Test
    void me_carriesIsAdminTrue_forAdminOrgUser() {
        String cookie = sessionCookieFor(adminOrgUserId, false);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .body("isAdmin", org.hamcrest.Matchers.equalTo(true));
    }

    @Test
    void me_carriesIsAdminFalse_forPlainOrgUser() {
        String cookie = sessionCookieFor(orgUserId, false);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .body("isAdmin", org.hamcrest.Matchers.equalTo(false));
    }

    @Test
    void anonymous_canListPublicProviders_andSeesNoSecrets() {
        // The login page must call this BEFORE any session exists.
        // The payload must not leak clientId, clientSecret, tenantId, allowedDomains.
        Response r = given().when().get("/api/v1/auth/providers");
        r.then().statusCode(200);
        // Two seeded rows: microsoft + google.
        assertThat(r.jsonPath().getList("providerKey")).contains("microsoft", "google");
        // Fail loudly if anyone accidentally exposes a credential field here.
        String body = r.asString();
        assertThat(body).doesNotContain("clientSecret");
        assertThat(body).doesNotContain("clientId");
        assertThat(body).doesNotContain("tenantId");
        assertThat(body).doesNotContain("allowedDomains");
    }

    @Test
    void anonymous_cannotHitMine() {
        given().when().get("/api/v1/peers/mine").then().statusCode(401);
    }

    @Test
    void localAdmin_cannotHitMine() {
        // Local admin has userId = null. /peers/mine has no row to scope to.
        Session s = createLocalAdminSession();
        given().cookie(SessionFilter.COOKIE_NAME, s.id)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(403);
    }

    @Test
    void orgUser_canListOwnPeers_emptyByDefault() {
        String cookie = sessionCookieFor(orgUserId, false);
        Response r = given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine");
        r.then().statusCode(200);
        assertThat(r.jsonPath().getList("$")).isEmpty();
    }

    @Test
    void promotion_takesEffectWithoutRelogin() {
        // Session created while user is plain; immediately promote in DB and
        // the next request must see isAdmin=true (SessionFilter re-reads each call).
        String cookie = sessionCookieFor(orgUserId, false);
        promote(orgUserId);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/users")
                .then().statusCode(200);
    }

    // -- helpers --------------------------------------------------------------

    @Transactional
    String persistUser(String name, String email, boolean isAdmin) {
        User u = User.createNew(name, email);
        u.isAdmin = isAdmin;
        u.persist();
        return u.id;
    }

    @Transactional
    void promote(String userId) {
        User u = User.findById(userId);
        u.isAdmin = true;
    }

    private String sessionCookieFor(String userId, boolean isAdmin) {
        // isAdmin is read from the User row by SessionFilter; we don't carry it
        // on the session itself. The boolean param exists only for read clarity.
        Session s = sessions.create(Session.MICROSOFT, "principal-" + userId.substring(0, 6), userId);
        return s.id;
    }

    private Session createLocalAdminSession() {
        return sessions.create(Session.LOCAL, "admin", null);
    }
}
