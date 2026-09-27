package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Self-service "soft delete" (users-self-delete-peer): a user can ask, from
 * "My access", to remove their own device. It disappears from their own
 * list and stops working immediately, but only an admin's real
 * {@code DELETE /api/v1/peers/{id}} removes the row — this is the queue for
 * that, not the deletion itself.
 */
@QuarkusTest
class MyPeerResourceDeletionTest {

    @Inject SessionService sessions;
    @Inject PeerService peerService;

    private final List<String> createdUserIds = new ArrayList<>();

    // Same reset as MyPeerResourceCreateMtuTest: this class sends its own
    // org-user cookie explicitly, and a leftover default spec from another
    // class must not silently win over it.
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
    void requestingDeletion_disablesThePeerAndHidesItFromListMine_butTheRowSurvives() {
        String cookie = orgUserSession();
        String peerId = persistPeer(currentUserId, "my-laptop");

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().delete("/api/v1/peers/mine/" + peerId)
                .then().statusCode(202)
                .body("id", org.hamcrest.Matchers.equalTo(peerId))
                .body("enabled", org.hamcrest.Matchers.equalTo(false))
                .body("deletionRequestedAt", org.hamcrest.Matchers.notNullValue());

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("id", not(hasItem(peerId)));

        Peer reloaded = Peer.findById(peerId);
        assertThat(reloaded).as("the row itself is not removed — only an admin's real delete does that").isNotNull();
        assertThat(reloaded.enabled).isFalse();
        assertThat(reloaded.deletionRequestedAt).isNotNull();
    }

    @Test
    void cannotRequestDeletionForSomeoneElsesPeer() {
        String victimUserId = persistUser("Victim", "victim-" + UUID.randomUUID() + "@firma.de");
        createdUserIds.add(victimUserId);
        String victimPeerId = persistPeer(victimUserId, "not-yours");

        String attackerCookie = orgUserSession();

        given().cookie(SessionFilter.COOKIE_NAME, attackerCookie)
                .when().delete("/api/v1/peers/mine/" + victimPeerId)
                .then().statusCode(404);

        Peer stillThere = Peer.findById(victimPeerId);
        assertThat(stillThere.deletionRequestedAt).as("an unrelated user's request must not touch it").isNull();
    }

    @Test
    void unknownPeerId_returns404() {
        String cookie = orgUserSession();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().delete("/api/v1/peers/mine/no-such-peer")
                .then().statusCode(404);
    }

    /** An admin re-enabling the peer is how they say "keep it" — found while
     *  verifying this feature in the browser: without this, the "removal
     *  requested" badge sat on an active peer forever with no UI to clear it. */
    @Test
    void adminReEnablingThePeer_clearsThePendingDeletionRequest() {
        String userId = persistUser("Kept After All", "kept-" + UUID.randomUUID() + "@firma.de");
        createdUserIds.add(userId);
        String peerId = persistPeer(userId, "kept-device");
        Session s = sessions.create(Session.MICROSOFT, "principal-" + userId.substring(0, 6), userId);

        given().cookie(SessionFilter.COOKIE_NAME, s.id)
                .when().delete("/api/v1/peers/mine/" + peerId).then().statusCode(202);
        assertThat(QuarkusTransaction.requiringNew().call(() -> Peer.<Peer>findById(peerId).deletionRequestedAt))
                .isNotNull();

        QuarkusTransaction.requiringNew().run(() -> peerService.setEnabled(peerId, true));

        Peer reloaded = QuarkusTransaction.requiringNew().call(() -> Peer.findById(peerId));
        assertThat(reloaded.enabled).isTrue();
        assertThat(reloaded.deletionRequestedAt).as("re-enabling clears the pending request").isNull();
    }

    @Test
    void requestingDeletionTwice_staysIdempotent() {
        String cookie = orgUserSession();
        String peerId = persistPeer(currentUserId, "asked-twice");

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().delete("/api/v1/peers/mine/" + peerId).then().statusCode(202);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().delete("/api/v1/peers/mine/" + peerId).then().statusCode(202);

        assertThat(Peer.<Peer>findById(peerId).enabled).isFalse();
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
    String persistPeer(String userId, String name) {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(userId, name, publicKey,
                "10.9.0." + (150 + (int) (Math.random() * 90)));
        p.persist();
        return p.id;
    }
}
