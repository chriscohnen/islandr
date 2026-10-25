package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * edge-peer-dns: which resolver a peer at an edge is told to use.
 *
 * <p>The hub's resolver listens on the hub's own tunnel address, and every
 * peer's config names it. For a peer that dials an edge that address is routed
 * into a tunnel ending at the edge, which has neither the resolver nor a route
 * to it. Name resolution then fails while the routing is perfectly fine —
 * which is the worst shape of broken, because nothing looks wrong.
 *
 * <p>So an edge names its own resolver or none, and never inherits the hub's.
 * Empty is the default on purpose: both alternatives depend on something
 * Islandr cannot see — a resolver at the edge, or a hand-built tunnel between
 * the hubs with a route for the hub's address (ADR-I-0003 keeps that tunnel
 * outside Islandr's knowledge). The operator states it; the software does not
 * guess.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EdgePeerDnsTest {

    private static final String MARK = "edgedns";
    private static final String KEY = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";

    /** The hub's own resolver, forced on for this class and put back after.
     *  Without it two of these tests would pass on an installation that has no
     *  DNS line at all — they would assert the absence of something that was
     *  never there. The suite shares one settings row, so it is restored. */
    private static final String HUB_DNS = "10.99.99.99";
    private String savedClientDns;

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        Peer.delete("edgeHubId in (select e.id from EdgeHub e where e.name like ?1)", MARK + "-%");
        Peer.delete("name like ?1", MARK + "-%");
        EdgeHub.delete("name like ?1", MARK + "-%");
        User.delete("email like ?1", MARK + "-%");
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) s.edgeHubsEnabled = true;   // precondition, not the subject
    }

    @BeforeEach
    @Transactional
    void forceHubDns() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) {
            savedClientDns = s.wgClientDns;
            s.wgClientDns = HUB_DNS;
        }
    }

    @AfterEach
    @Transactional
    void restoreHubDns() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) s.wgClientDns = savedClientDns;
    }

    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(211);

    private String nextIp() {
        return "10.8.0." + host.getAndIncrement();
    }

    @Transactional
    String createUser() {
        User u = User.createNew("Edge Dns", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.persist();
        return u.id;
    }

    private String createEdge(String dnsServerIp) {
        StringBuilder b = new StringBuilder("{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\"");
        b.append(",\"enabled\":true,\"lat\":-33.45,\"lng\":-70.67");
        b.append(",\"endpoint\":\"edge.example:51820\",\"serverPublicKey\":\"").append(KEY).append("\"");
        if (dnsServerIp != null) b.append(",\"dnsServerIp\":\"").append(dnsServerIp).append("\"");
        return given().contentType("application/json").body(b.append("}").toString())
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");
    }

    private String createPeerAt(String edgeId) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\",\"assignedIp\":\"" + nextIp()
                        + "\",\"type\":\"client\",\"edgeHubId\":\"" + edgeId + "\"}")
                .when().post("/api/v1/users/" + createUser() + "/peers")
                .then().statusCode(201).extract().path("peer.id");
    }

    @Test
    void anEdgeWithoutAResolverGivesItsPeersNoDnsLine() {
        String peerId = createPeerAt(createEdge(null));
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", not(containsString("DNS =")));
    }

    @Test
    void anEdgeWithAResolverNamesIt() {
        String peerId = createPeerAt(createEdge("10.60.0.1"));
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", containsString("DNS = 10.60.0.1"));
    }

    /** The one that would be silent: inheriting the hub's resolver address
     *  produces a config that looks complete and cannot resolve a name. */
    @Test
    void anEdgePeerNeverInheritsTheHubsResolver() {
        String peerId = createPeerAt(createEdge(null));
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", not(containsString(HUB_DNS)));
    }

    /** The counter-test, and the reason this is a parameter and not a deletion:
     *  a peer at the central hub still gets the hub's resolver. */
    @Test
    void aPeerAtTheCentralHubStillGetsTheHubsResolver() {
        String peerId = given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-central\",\"assignedIp\":\"" + nextIp()
                        + "\",\"type\":\"client\"}")
                .when().post("/api/v1/users/" + createUser() + "/peers")
                .then().statusCode(201).extract().path("peer.id");
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", containsString("DNS = " + HUB_DNS));
    }

    @Test
    void theResolverIsStoredAndReturned() {
        String id = createEdge("10.60.0.1");
        given().when().get("/api/v1/edge-hubs/" + id)
                .then().statusCode(200)
                .body("dnsServerIp", is("10.60.0.1"));
    }

    @Test
    void aMalformedResolverAddressIsRefused() {
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-bad\",\"enabled\":true,\"lat\":0,\"lng\":0,"
                        + "\"dnsServerIp\":\"not-an-ip\"}")
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(400);
    }
}
