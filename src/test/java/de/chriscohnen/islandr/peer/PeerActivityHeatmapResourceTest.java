package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

/** Covers {@code GET /api/v1/peers/activity-heatmap} (#32). */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerActivityHeatmapResourceTest {

    @BeforeEach
    void cleanup() { wipe(); }

    @AfterEach
    void teardown() { wipe(); }

    @Transactional
    void wipe() {
        PeerDailyActivity.deleteAll();
        Peer.deleteAll();
        User.deleteAll();
    }

    @Transactional
    String createPeer(String name) {
        User u = User.createNew("Owner " + UUID.randomUUID(), "owner-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = u.id;
        p.name = name;
        p.publicKey = "pk-" + UUID.randomUUID();
        p.assignedIp = "10.9.0." + (1 + (int) (Math.random() * 200));
        p.enabled = true;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.type = "client";
        p.persist();
        return p.id;
    }

    @Transactional
    void bumpToday(String peerId, int hits) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        PeerDailyActivity row = new PeerDailyActivity(peerId, today);
        row.sampleHits = hits;
        row.persist();
    }

    @Transactional
    void bumpTodayWithBytes(String peerId, long rx, long tx) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        PeerDailyActivity row = new PeerDailyActivity(peerId, today);
        row.sampleHits = 1;
        row.rxBytes = rx;
        row.txBytes = tx;
        row.persist();
    }

    record PeerAndOwner(String peerId, String userId, String userName) {}

    @Transactional
    PeerAndOwner createPeerWithNamedOwner(String peerName, String ownerName) {
        User u = User.createNew(ownerName, "owner-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = u.id;
        p.name = peerName;
        p.publicKey = "pk-" + UUID.randomUUID();
        p.assignedIp = "10.9.0." + (1 + (int) (Math.random() * 200));
        p.enabled = true;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.type = "client";
        p.persist();
        return new PeerAndOwner(p.id, u.id, ownerName);
    }

    @Test
    void defaultWindow_is30Days() {
        given().when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("days", hasSize(30));
    }

    @Test
    void daysParam_isClampedTo180() {
        given().queryParam("days", 9999).when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("days", hasSize(180));
    }

    @Test
    void includesEveryPeer_evenWithoutActivity() {
        String peerId = createPeer("silent-peer");
        given().when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + peerId + "' }.name", equalTo("silent-peer"))
                .body("peers.find { it.peerId == '" + peerId + "' }.sampleHits", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    void reflectsTodaysSampleHits() {
        String peerId = createPeer("busy-peer");
        bumpToday(peerId, 42);
        given().queryParam("days", 1).when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + peerId + "' }.sampleHits[0]", equalTo(42));
    }

    @Test
    void reflectsTodaysBytes() {
        String peerId = createPeer("chatty-peer");
        bumpTodayWithBytes(peerId, 12345L, 6789L);
        given().queryParam("days", 1).when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + peerId + "' }.rxBytes[0]", equalTo(12345))
                .body("peers.find { it.peerId == '" + peerId + "' }.txBytes[0]", equalTo(6789));
    }

    /**
     * Naming is per-user and free-form: two peers could both be called
     * "Laptop" and be indistinguishable in the matrix without knowing who
     * they belong to. The row carries the owner's id and name so the
     * frontend can prepend an avatar (#dashboard-peer-owner).
     */
    @Test
    void rowCarriesTheOwningUsersIdAndName() {
        PeerAndOwner owned = createPeerWithNamedOwner("Laptop", "Jonas Weber");
        given().when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + owned.peerId() + "' }.userId", equalTo(owned.userId()))
                .body("peers.find { it.peerId == '" + owned.peerId() + "' }.userName", equalTo("Jonas Weber"));
    }

    @Transactional
    String createSitePeer(String name) {
        Peer site = Peer.createNew(null, name,
                "SITEPUBKEY" + UUID.randomUUID().toString().replace("-", "").substring(0, 20) + "=", "10.8.0.241");
        site.type = "site";
        site.persist();
        return site.id;
    }

    /** A site peer has no owning user — the row must say so, not guess. */
    @Test
    void sitePeerRow_hasNoOwner() {
        String siteId = createSitePeer("site-gw-" + UUID.randomUUID());
        given().when().get("/api/v1/peers/activity-heatmap")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + siteId + "' }.userId", equalTo(null))
                .body("peers.find { it.peerId == '" + siteId + "' }.userName", equalTo(null));
    }
}
