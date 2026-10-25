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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Self-service half of peer-traffic-ranking ({@code GET
 * /api/v1/peers/mine/traffic-ranking}) — a user should see exactly the
 * figures an admin's {@code /api/v1/peers/traffic-ranking} would show for
 * their own peers, restricted to those peers and nothing else. Persists its
 * own admin session rather than using AdminSessionExtension, since that
 * extension's default-cookie spec and this class's explicit per-test org-
 * user cookies would otherwise fight over RestAssured's static request spec.
 */
@QuarkusTest
class MyPeerTrafficRankingResourceTest {

    @Inject SessionService sessions;

    private final List<String> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestAssured.requestSpecification = null;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        for (String userId : createdUserIds) {
            List<Peer> myPeers = Peer.list("userId", userId);
            for (Peer p : myPeers) PeerDailyActivity.delete("id.peerId", p.id);
            Peer.delete("userId", userId);
            Session.delete("userId", userId);
            User.deleteById(userId);
        }
        createdUserIds.clear();
    }

    @Test
    void seesOnlyOwnPeersTotal() {
        String ownerCookie = orgUserSession("owner");
        String myPeerId = persistPeer(currentUserId, "my-laptop");
        addActivity(myPeerId, LocalDate.now(ZoneOffset.UTC), 5_000L, 5_000L);

        String otherUserId = persistUser("other").id;
        createdUserIds.add(otherUserId);
        String otherPeerId = persistPeer(otherUserId, "someone-elses-peer");
        addActivity(otherPeerId, LocalDate.now(ZoneOffset.UTC), 999_000L, 999_000L);

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine/traffic-ranking")
                .then().statusCode(200)
                .body("peers.size()", equalTo(1))
                .body("peers[0].peerId", equalTo(myPeerId))
                .body("peers[0].totalBytes", equalTo(10_000));
    }

    @Test
    void matchesTheAdminEndpointsFigureForTheSamePeer() {
        String ownerCookie = orgUserSession("owner2");
        String myPeerId = persistPeer(currentUserId, "my-desktop");
        addActivity(myPeerId, LocalDate.now(ZoneOffset.UTC), 1_234L, 5_678L);
        String adminCookie = adminSession();

        Number mine = given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine/traffic-ranking")
                .then().statusCode(200).extract()
                .path("peers.find { it.peerId == '" + myPeerId + "' }.totalBytes");

        Number admins = given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200).extract()
                .path("peers.find { it.peerId == '" + myPeerId + "' }.totalBytes");

        org.assertj.core.api.Assertions.assertThat(mine.longValue()).isEqualTo(admins.longValue()).isEqualTo(6_912L);
    }

    @Test
    void noOwnTraffic_returnsAnEmptyList() {
        String ownerCookie = orgUserSession("owner3");
        persistPeer(currentUserId, "quiet-peer");

        given().cookie(SessionFilter.COOKIE_NAME, ownerCookie)
                .when().get("/api/v1/peers/mine/traffic-ranking")
                .then().statusCode(200)
                .body("peers.size()", equalTo(0));
    }

    @Test
    void adminViewAs_seesTheTargetUsersTraffic() {
        String adminCookie = adminSession();
        String targetUserId = persistUser("viewed").id;
        createdUserIds.add(targetUserId);
        String targetPeerId = persistPeer(targetUserId, "viewed-peer");
        addActivity(targetPeerId, LocalDate.now(ZoneOffset.UTC), 100L, 200L);

        given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .queryParam("userId", targetUserId)
                .when().get("/api/v1/peers/mine/traffic-ranking")
                .then().statusCode(200)
                .body("peers[0].peerId", equalTo(targetPeerId))
                .body("peers[0].totalBytes", equalTo(300));
    }

    @Test
    void nonAdminCannotViewAsAnotherUser() {
        String attackerCookie = orgUserSession("attacker");
        String victimUserId = persistUser("victim").id;
        createdUserIds.add(victimUserId);

        given().cookie(SessionFilter.COOKIE_NAME, attackerCookie)
                .queryParam("userId", victimUserId)
                .when().get("/api/v1/peers/mine/traffic-ranking")
                .then().statusCode(403);
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

    private String adminSession() {
        User u = persistAdminUser();
        createdUserIds.add(u.id);
        Session s = sessions.create(Session.MICROSOFT, "principal-" + u.id.substring(0, 6), u.id);
        return s.id;
    }

    @Transactional
    User persistAdminUser() {
        User u = User.createNew("Test admin", "admin-" + UUID.randomUUID() + "@firma.de");
        u.isAdmin = true;
        u.persist();
        return u;
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
                TestPeerIps.next());
        p.persist();
        return p.id;
    }

    @Transactional
    void addActivity(String peerId, LocalDate day, long rx, long tx) {
        PeerDailyActivity row = new PeerDailyActivity(peerId, day);
        row.sampleHits = 1;
        row.rxBytes = rx;
        row.txBytes = tx;
        row.persist();
    }
}
