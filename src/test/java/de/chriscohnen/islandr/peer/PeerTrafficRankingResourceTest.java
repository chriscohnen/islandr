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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers {@code GET /api/v1/peers/traffic-ranking} (peer-traffic-ranking) —
 * who consumed the most tunnel traffic over a billing-sized window, since a
 * metered VPS cares about that and the heatmap only shows a day x peer grid,
 * not a ranking.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerTrafficRankingResourceTest {

    @BeforeEach
    void cleanup() { wipe(); }

    @AfterEach
    void teardown() { wipe(); }

    // Deliberately does NOT delete User rows: the ENV-bootstrap admin session
    // (AdminSessionExtension) binds to a real admin@local User row when one
    // exists (AdminUserBootstrap), and wiping it out from under an
    // already-issued session cookie 401s every request for the rest of the
    // run — reproduced while writing this test, not a theoretical risk.
    @Transactional
    void wipe() {
        PeerDailyActivity.deleteAll();
        Peer.deleteAll();
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
    void addActivity(String peerId, LocalDate day, long rx, long tx) {
        PeerDailyActivity row = new PeerDailyActivity(peerId, day);
        row.sampleHits = 1;
        row.rxBytes = rx;
        row.txBytes = tx;
        row.persist();
    }

    @Test
    void defaultWindow_isThisMonth() {
        given().when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("window", equalTo("this-month"));
    }

    @Test
    void ranksByTotalBytesDescending() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String small = createPeer("small-consumer");
        String big = createPeer("big-consumer");
        addActivity(small, today, 1_000L, 1_000L);
        addActivity(big, today, 50_000L, 50_000L);

        given().when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("peers[0].peerId", equalTo(big))
                .body("peers[0].totalBytes", equalTo(100_000))
                .body("peers[1].peerId", equalTo(small))
                .body("peers[1].totalBytes", equalTo(2_000));
    }

    @Test
    void excludesPeersWithNoTrafficInTheWindow() {
        String quiet = createPeer("quiet-peer");
        given().when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + quiet + "' }", equalTo(null));
    }

    @Test
    void lastMonthWindow_excludesThisMonthsActivity() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String peerId = createPeer("this-month-peer");
        addActivity(peerId, today, 5_000L, 5_000L);

        given().queryParam("window", "last-month").when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("window", equalTo("last-month"))
                .body("peers.find { it.peerId == '" + peerId + "' }", equalTo(null));
    }

    @Test
    void lastMonthWindow_includesLastMonthsActivity() {
        YearMonth lastMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1);
        LocalDate dayLastMonth = lastMonth.atDay(1);
        String peerId = createPeer("last-month-peer");
        addActivity(peerId, dayLastMonth, 3_000L, 4_000L);

        given().queryParam("window", "last-month").when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + peerId + "' }.totalBytes", equalTo(7_000));
    }

    @Test
    void sumsAcrossMultipleDaysInTheWindow() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String peerId = createPeer("multi-day-peer");
        addActivity(peerId, today, 1_000L, 1_000L);
        if (today.getDayOfMonth() > 1) {
            addActivity(peerId, today.minusDays(1), 2_000L, 2_000L);
        }

        long expected = today.getDayOfMonth() > 1 ? 6_000L : 2_000L;
        given().when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + peerId + "' }.totalBytes", equalTo((int) expected));
    }

    @Test
    void rowCarriesOwnerNameAndPeerName() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String peerId = createPeer("Named-Laptop");
        addActivity(peerId, today, 10L, 20L);

        given().when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("peers.find { it.peerId == '" + peerId + "' }.peerName", equalTo("Named-Laptop"))
                .body("peers.find { it.peerId == '" + peerId + "' }.userName", notNullValue());
    }

    @Test
    void invalidWindow_fallsBackToThisMonth() {
        given().queryParam("window", "bogus").when().get("/api/v1/peers/traffic-ranking")
                .then().statusCode(200)
                .body("window", equalTo("this-month"));
    }
}
