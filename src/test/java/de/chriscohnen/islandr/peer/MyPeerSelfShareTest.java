package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.settings.Settings;
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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * peer-self-share: an owner shares one of their own peer's ports with a
 * single named colleague, without ever touching the ACL matrix — see
 * PeerSelfShareService's own doc comment for the design, and FirewallTest's
 * peer-self-share cases for the RuleBuilder half.
 */
@QuarkusTest
class MyPeerSelfShareTest {

    @Inject SessionService sessions;

    private final List<String> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestAssured.requestSpecification = null;
        setFeatureEnabled(true);
    }

    @AfterEach
    @Transactional
    void cleanup() {
        setFeatureEnabled(false);
        for (String userId : createdUserIds) {
            // peer_self_shares has ON DELETE CASCADE on both owner_peer_id and
            // target_user_id (V83) — deleting the peers/user below removes any
            // share rows for this test automatically, no separate cleanup needed.
            Peer.delete("userId", userId);
            Session.delete("userId", userId);
            User.deleteById(userId);
        }
        createdUserIds.clear();
    }

    @Transactional
    void setFeatureEnabled(boolean enabled) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).peerSelfShareEnabled = enabled;
    }

    @Test
    void ownerCreatesAndListsAShare_forAnExistingColleague() {
        String ownerCookie = orgUserSession("owner");
        String peerId = persistPeer(currentUserId, "owner-device");
        String colleagueEmail = newColleague("colleague-a");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + colleagueEmail + "\",\"port\":3000,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(201)
                .body("targetUserEmail", is(colleagueEmail))
                .body("port", is(3000))
                .body("live", is(true))
                .body("id", notNullValue());

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].targetUserEmail", is(colleagueEmail));
    }

    @Test
    void ownerRevokesTheirOwnShare() {
        String ownerCookie = orgUserSession("owner2");
        String peerId = persistPeer(currentUserId, "owner2-device");
        String colleagueEmail = newColleague("colleague-b");

        String shareId = given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + colleagueEmail + "\",\"port\":3001,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(201).extract().path("id");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().delete("/api/v1/peers/mine/shares/" + shareId)
                .then().statusCode(204);

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(200)
                .body("[0].live", is(false));
    }

    @Test
    void rejectsAPortAtOrBelow1024() {
        String ownerCookie = orgUserSession("owner3");
        String peerId = persistPeer(currentUserId, "owner3-device");
        String colleagueEmail = newColleague("colleague-c");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + colleagueEmail + "\",\"port\":1024,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(400);
    }

    @Test
    void rejectsAnUnknownColleagueEmail() {
        String ownerCookie = orgUserSession("owner4");
        String peerId = persistPeer(currentUserId, "owner4-device");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"nobody-" + UUID.randomUUID() + "@firma.de\",\"port\":3000,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(400);
    }

    @Test
    void rejectsSharingWithYourself() {
        String ownerCookie = orgUserSession("owner5");
        String peerId = persistPeer(currentUserId, "owner5-device");
        String ownEmail = User.<User>findById(currentUserId).email;

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + ownEmail + "\",\"port\":3000,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(400);
    }

    @Test
    void isForbiddenWhenTheAdminHasNotEnabledTheFeature() {
        setFeatureEnabled(false);
        String ownerCookie = orgUserSession("owner6");
        String peerId = persistPeer(currentUserId, "owner6-device");
        String colleagueEmail = newColleague("colleague-d");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + colleagueEmail + "\",\"port\":3000,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(403);
    }

    @Test
    void listMine_showsActiveShareCount_withoutOpeningShareDialog() {
        String ownerCookie = orgUserSession("owner7");
        String peerId = persistPeer(currentUserId, "owner7-device");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].activeShareCount", is(0));

        String colleagueEmail = newColleague("colleague-f");
        String shareId = given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + colleagueEmail + "\",\"port\":3000,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + peerId + "/shares")
                .then().statusCode(201).extract().path("id");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].activeShareCount", is(1));

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().delete("/api/v1/peers/mine/shares/" + shareId)
                .then().statusCode(204);

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].activeShareCount", is(0));
    }

    @Test
    void cannotShareAPeerYouDoNotOwn() {
        String victimUserId = persistUser("victim").id;
        createdUserIds.add(victimUserId);
        String victimPeerId = persistPeer(victimUserId, "victim-device");

        String attackerCookie = orgUserSession("attacker");
        String colleagueEmail = newColleague("colleague-e");

        given().cookie(SessionFilter.COOKIE_NAME, attackerCookie)
                .contentType("application/json")
                .body("{\"targetEmail\":\"" + colleagueEmail + "\",\"port\":3000,\"durationMinutes\":60}")
                .when().post("/api/v1/peers/mine/" + victimPeerId + "/shares")
                .then().statusCode(404);
    }

    // -- helpers --------------------------------------------------------------

    private String currentUserId;

    private String orgUserSession(String tag) {
        User u = persistUser(tag);
        createdUserIds.add(u.id);
        currentUserId = u.id;
        Session s = sessions.create(Session.MICROSOFT, "principal-" + u.id.substring(0, 6), u.id);
        return s.id;
    }

    private String newColleague(String tag) {
        User u = persistUser(tag);
        createdUserIds.add(u.id);
        return u.email;
    }

    @Transactional
    User persistUser(String tag) {
        User u = User.createNew("Test " + tag, tag + "-" + UUID.randomUUID() + "@firma.de");
        u.isAdmin = false;
        u.persist();
        return u;
    }

    @Transactional
    String persistPeer(String userId, String name) {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(userId, name, publicKey,
                "10.9.1." + (1 + (int) (Math.random() * 250)));
        p.persist();
        return p.id;
    }
}
