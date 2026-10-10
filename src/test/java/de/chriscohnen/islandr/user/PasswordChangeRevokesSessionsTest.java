package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * session-revoke-on-password-change: a password change/reset used to leave
 * every existing session of that account running — the worst case being an
 * admin "fixing" a compromised account by resetting its password while the
 * attacker's own session carries on unaffected for up to {@code TTL} (12h).
 *
 * <p>Two distinct outcomes, both exercised here:
 * <ul>
 *   <li>Admin resets *someone else's* password — every session of that
 *       account ends.</li>
 *   <li>An admin changes *their own* password (the only self-service path
 *       this admin-only endpoint has, since it's reachable on any id
 *       including the caller's own) — every *other* session of that account
 *       ends, but the session making the call survives. Changing your own
 *       password must not log you out of the browser tab you changed it
 *       from.</li>
 * </ul>
 */
@QuarkusTest
class PasswordChangeRevokesSessionsTest {

    @Inject SessionService sessions;

    private final java.util.List<String> createdUserIds = new java.util.ArrayList<>();

    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        for (String id : createdUserIds) {
            Session.delete("userId", id);
            User.deleteById(id);
        }
        createdUserIds.clear();
    }

    @Test
    void resettingAnotherUsersPassword_revokesAllTheirSessions() {
        String targetId = persistUser("Target", false);
        String s1 = sessions.create(Session.MICROSOFT, "target-principal-1", targetId).id;
        String s2 = sessions.create(Session.MICROSOFT, "target-principal-2", targetId).id;
        String adminCookie = adminLogin();

        given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .contentType("application/json")
                .body("{ \"password\": \"a-fine-new-password\" }")
                .when().put("/api/v1/users/" + targetId + "/password")
                .then().statusCode(200)
                .body("sessionsRevoked", is(2));

        assertSessionDead(s1);
        assertSessionDead(s2);
    }

    @Test
    void clearingAnotherUsersPassword_alsoRevokesTheirSessions() {
        String targetId = persistUser("Target", false);
        setPasswordDirectly(targetId, "whatever-password");
        String s1 = sessions.create(Session.MICROSOFT, "target-principal", targetId).id;
        String adminCookie = adminLogin();

        given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .contentType("application/json")
                .body("{ \"password\": \"\" }")
                .when().put("/api/v1/users/" + targetId + "/password")
                .then().statusCode(200)
                .body("sessionsRevoked", is(1));

        assertSessionDead(s1);
    }

    @Test
    void adminChangingTheirOwnPassword_keepsTheCallingSessionAlive_butEndsOthers() {
        String adminUserId = persistUser("Self-Admin", true);
        String callingSessionId = sessions.create(Session.LOCAL, "self-admin-principal", adminUserId).id;
        String otherDeviceSessionId = sessions.create(Session.LOCAL, "self-admin-principal", adminUserId).id;

        given().cookie(SessionFilter.COOKIE_NAME, callingSessionId)
                .contentType("application/json")
                .body("{ \"password\": \"a-new-password-123\" }")
                .when().put("/api/v1/users/" + adminUserId + "/password")
                .then().statusCode(200)
                .body("sessionsRevoked", is(1));

        assertSessionAlive(callingSessionId);
        assertSessionDead(otherDeviceSessionId);
    }

    @Test
    void auditLogNamesTheSessionCount() {
        String targetId = persistUser("Audited", false);
        sessions.create(Session.MICROSOFT, "audited-principal", targetId);
        String uniqueMarker = "audit-check-" + UUID.randomUUID();
        String adminCookie = adminLogin();

        given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .contentType("application/json")
                .body("{ \"password\": \"" + uniqueMarker.substring(0, 20) + "x\" }")
                .when().put("/api/v1/users/" + targetId + "/password")
                .then().statusCode(200);

        given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .when().get("/api/v1/audit?action=user.password_set&limit=5")
                .then().statusCode(200)
                .body("meta[0]", containsString("sessionsRevoked"));
    }

    private void assertSessionDead(String sessionId) {
        org.assertj.core.api.Assertions.assertThat(sessions.findActive(sessionId)).isNull();
    }

    private void assertSessionAlive(String sessionId) {
        org.assertj.core.api.Assertions.assertThat(sessions.findActive(sessionId)).isNotNull();
    }

    @Transactional
    String persistUser(String label, boolean isAdmin) {
        User u = User.createNew(label + "-" + UUID.randomUUID(), label.toLowerCase() + "-" + UUID.randomUUID() + "@firma.de");
        u.isAdmin = isAdmin;
        u.persist();
        createdUserIds.add(u.id);
        return u.id;
    }

    @Transactional
    void setPasswordDirectly(String userId, String password) {
        User u = User.findById(userId);
        u.passwordHash = "irrelevant-for-this-test-" + password;
    }

    private static String adminLogin() {
        var resp = given().contentType("application/json")
                .body("{\"username\":\"admin\",\"password\":\"test-admin-pw\"}")
                .when().post("/api/v1/auth/login");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("test admin login failed (status=" + resp.statusCode() + ")");
        }
        return resp.getDetailedCookie(SessionFilter.COOKIE_NAME).getValue();
    }
}
