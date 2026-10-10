package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code GET /api/v1/peers/mine/{id}/conf} under admin "view as" (My
 * access's QR/.conf button is shown even while impersonating — unlike edit,
 * share and remove, which hide behind {@code v-if="!viewAsUserId""}). Before
 * this fix the endpoint always resolved the caller's own identity, so an
 * admin previewing another user's page got a 404 on every peer that wasn't
 * the admin's own — same fix shape as {@code activityHeatmap} (#43 follow-up).
 */
@QuarkusTest
class MyPeerResourceReshowImpersonationTest {

    @Inject SessionService sessions;

    private final List<String> createdUserIds = new ArrayList<>();

    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        for (String userId : createdUserIds) {
            Peer.delete("userId", userId);
            Session.delete("userId", userId);
            User.deleteById(userId);
        }
        createdUserIds.clear();
    }

    @Test
    void adminViewingAsAnotherUser_canReshowThatUsersOwnPeer() {
        String targetUserId = persistUser("Chris", "chris-" + UUID.randomUUID() + "@firma.de");
        String peerId = persistPeer(targetUserId, "chris-laptop");
        String adminCookie = adminLogin();

        given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .queryParam("userId", targetUserId)
                .when().get("/api/v1/peers/mine/" + peerId + "/conf")
                .then().statusCode(200)
                .body("peer.id", org.hamcrest.Matchers.equalTo(peerId))
                .body("conf", notNullValue());
    }

    private String adminLogin() {
        var resp = given().contentType("application/json")
                .body("{\"username\":\"admin\",\"password\":\"test-admin-pw\"}")
                .when().post("/api/v1/auth/login");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(
                    "test admin login failed (status=" + resp.statusCode() +
                    ") — check %test.islandr.admin.* in application.properties");
        }
        return resp.getDetailedCookie(SessionFilter.COOKIE_NAME).getValue();
    }

    @Test
    void nonAdminOrgUser_cannotUseUserIdParamToReadSomeoneElsesPeer() {
        String ownerUserId = persistUser("Chris", "chris-" + UUID.randomUUID() + "@firma.de");
        String peerId = persistPeer(ownerUserId, "chris-laptop");

        String callerCookie = orgUserSession();

        given().cookie(SessionFilter.COOKIE_NAME, callerCookie)
                .queryParam("userId", ownerUserId)
                .when().get("/api/v1/peers/mine/" + peerId + "/conf")
                .then().statusCode(403);
    }

    private String orgUserSession() {
        String userId = persistUser("Caller", "caller-" + UUID.randomUUID() + "@firma.de");
        Session s = sessions.create(Session.MICROSOFT, "principal-" + userId.substring(0, 6), userId);
        return s.id;
    }

    @Transactional
    String persistUser(String name, String email) {
        User u = User.createNew(name, email);
        u.isAdmin = false;
        u.persist();
        createdUserIds.add(u.id);
        return u.id;
    }

    @Transactional
    String persistPeer(String userId, String name) {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(userId, name, publicKey,
                "10.9.0." + (1 + (int) (Math.random() * 200)));
        p.persist();
        return p.id;
    }
}
