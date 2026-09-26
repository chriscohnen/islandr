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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-service rename and re-categorisation (issue: peer-self-edit): a user
 * can change a device's name and category from "My access" without deleting
 * and re-adding it. Deliberately narrow — IP, CIDR and ownership stay behind
 * {@code Auth.requireAdmin} on {@code PUT /api/v1/peers/{id}}, never reachable
 * here.
 */
@QuarkusTest
class MyPeerResourceEditTest {

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
    void renamesAndRecategorisesAnOwnPeer() {
        String cookie = orgUserSession();
        String peerId = persistPeer(currentUserId, "old-name", "laptop");

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("{\"name\":\"new-name\",\"deviceType\":\"mobile\"}")
                .when().put("/api/v1/peers/mine/" + peerId)
                .then().statusCode(200)
                .body("name", org.hamcrest.Matchers.equalTo("new-name"))
                .body("deviceType", org.hamcrest.Matchers.equalTo("mobile"));

        Peer reloaded = Peer.findById(peerId);
        assertThat(reloaded.name).isEqualTo("new-name");
        assertThat(reloaded.deviceType).isEqualTo("mobile");
    }

    @Test
    void blankDeviceTypeClearsTheCategory() {
        String cookie = orgUserSession();
        String peerId = persistPeer(currentUserId, "categorised", "server");

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("{\"name\":\"categorised\",\"deviceType\":\"\"}")
                .when().put("/api/v1/peers/mine/" + peerId)
                .then().statusCode(200)
                .body("deviceType", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void cannotEditSomeoneElsesPeer() {
        String victimUserId = persistUser("Victim", "victim-" + UUID.randomUUID() + "@firma.de");
        createdUserIds.add(victimUserId);
        String victimPeerId = persistPeer(victimUserId, "not-yours", null);

        String attackerCookie = orgUserSession();

        given().cookie(SessionFilter.COOKIE_NAME, attackerCookie)
                .contentType("application/json")
                .body("{\"name\":\"pwned\"}")
                .when().put("/api/v1/peers/mine/" + victimPeerId)
                .then().statusCode(404);

        assertThat(Peer.<Peer>findById(victimPeerId).name).isEqualTo("not-yours");
    }

    @Test
    void blankNameIsRejected() {
        String cookie = orgUserSession();
        String peerId = persistPeer(currentUserId, "keep-me", null);

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("{\"name\":\"\"}")
                .when().put("/api/v1/peers/mine/" + peerId)
                .then().statusCode(400);

        assertThat(Peer.<Peer>findById(peerId).name).isEqualTo("keep-me");
    }

    @Test
    void unknownPeerId_returns404() {
        String cookie = orgUserSession();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("{\"name\":\"anything\"}")
                .when().put("/api/v1/peers/mine/no-such-peer")
                .then().statusCode(404);
    }

    private String currentUserId;

    private String orgUserSession() {
        String userId = persistUser("Portal User", "portal-" + UUID.randomUUID() + "@firma.de");
        createdUserIds.add(userId);
        currentUserId = userId;
        Session s = sessions.create(Session.MICROSOFT, "principal-" + userId.substring(0, 6), userId);
        return s.id;
    }

    @Transactional
    String persistUser(String name, String email) {
        User u = User.createNew(name, email);
        u.isAdmin = false;
        u.persist();
        return u.id;
    }

    @Transactional
    String persistPeer(String userId, String name, String deviceType) {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(userId, name, publicKey,
                "10.9.0." + (150 + (int) (Math.random() * 90)));
        p.deviceType = deviceType;
        p.persist();
        return p.id;
    }
}
