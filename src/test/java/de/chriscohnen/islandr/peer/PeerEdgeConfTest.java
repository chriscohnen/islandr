package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.acl.Site;
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
import static org.hamcrest.Matchers.not;

/**
 * peer-edge-config-variant (ADR-I-0003): the {@code .conf} of a peer assigned
 * to an edge hub must actually point at that edge.
 *
 * <p>Until this slice the assignment was recorded correctly and the file was
 * wrong — it carried the central hub's endpoint and server key, so the one
 * useful artefact of the whole feature sent the device to the wrong machine.
 *
 * <p>Three values differ and each is tested on its own, because each fails
 * differently: a wrong <b>key</b> means the handshake is rejected and the user
 * sees "no connection"; a wrong <b>endpoint</b> means the traffic leaves for
 * another continent and still works, which is worse because nobody
 * investigates; wrong <b>AllowedIPs</b> means a black hole for exactly the
 * networks the device can reach, and only for some of them.
 *
 * <p>Both the create response and the re-show are checked. They are two code
 * paths through the same renderer, and the re-show is the one a user actually
 * reaches twice.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerEdgeConfTest {

    private static final String MARK = "edgeconf";
    private static final String EDGE_KEY = "ZWRnZUh1YlNlcnZlcktleUZvclRlc3RzUGFkZGluZz0=";
    private static final String EDGE_ENDPOINT = "edge.santiago.test:51820";

    /** Covers both site CIDRs below, so a central peer gets the supernet and
     *  neither site listed individually — and an edge peer must get the
     *  opposite. */
    private static final String SUPERNET = "10.60.0.0/15";
    private static final String EDGE_SITE_CIDR = "10.60.7.0/24";
    private static final String CENTRAL_SITE_CIDR = "10.61.7.0/24";

    /** Own address block — the allocator hands out the low addresses that other
     *  test classes hardcode (same reason as PeerHubAssignmentTest). */
    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(181);

    private String nextIp() {
        return "10.8.0." + host.getAndIncrement();
    }

    private String centralKey;
    private String centralEndpoint;
    private String savedTunnelMode;
    private String savedAllowedIpsMode;
    private String savedSupernet;

    @BeforeEach
    @Transactional
    void arrange() {
        wipeRows();
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        // Read, never write: SettingsResourceTest asserts the seeded central
        // key, so this class asserts against whatever is there instead of
        // setting its own.
        centralKey = s.wgServerPublicKey;
        centralEndpoint = s.wgServerEndpoint;
        // The split-tunnel fields are the subject, so they are forced — and
        // put back in teardown, because Settings is one row for the whole
        // suite and the next class did not ask for these values.
        savedTunnelMode = s.tunnelMode;
        savedAllowedIpsMode = s.allowedIpsMode;
        savedSupernet = s.splitSupernet;
        s.tunnelMode = "SPLIT";
        s.allowedIpsMode = "AUTO";
        s.splitSupernet = SUPERNET;
        s.edgeHubsEnabled = true;   // precondition, not the subject
    }

    @AfterEach
    @Transactional
    void restore() {
        wipeRows();
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) {
            s.tunnelMode = savedTunnelMode;
            s.allowedIpsMode = savedAllowedIpsMode;
            s.splitSupernet = savedSupernet;
            s.edgeHubsEnabled = false;
        }
    }

    void wipeRows() {
        Site.delete("name like ?1", MARK + "-%");
        Peer.delete("edgeHubId in (select e.id from EdgeHub e where e.name like ?1)", MARK + "-%");
        Peer.delete("name like ?1", MARK + "-%");
        EdgeHub.delete("name like ?1", MARK + "-%");
        User.delete("email like ?1", MARK + "-%");
    }

    @Transactional
    String createEdge() {
        EdgeHub e = EdgeHub.createNew(MARK + "-" + UUID.randomUUID(), -33.45, -70.67, null);
        e.endpoint = EDGE_ENDPOINT;
        e.serverPublicKey = EDGE_KEY;
        e.persist();
        return e.id;
    }

    @Transactional
    String createUser() {
        User u = User.createNew("Edge Conf", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.persist();
        return u.id;
    }

    /** A site counts towards AllowedIPs only through an enabled gateway peer,
     *  so the gateway is part of the fixture, not decoration. */
    @Transactional
    void createSiteBehind(String cidr, String edgeHubId) {
        Peer gw = Peer.createNew(null, MARK + "-gw-" + UUID.randomUUID(), "gw" + UUID.randomUUID(), nextIp());
        gw.type = "site";
        gw.persist();
        Site s = Site.createNew(MARK + "-" + UUID.randomUUID(), cidr, null);
        s.edgeHubId = edgeHubId;
        s.gatewayPeerId = gw.id;
        s.persist();
    }

    private String createPeer(String userId, String edgeHubId) {
        String body = "{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\",\"assignedIp\":\"" + nextIp()
                + "\",\"type\":\"client\""
                + (edgeHubId != null ? ",\"edgeHubId\":\"" + edgeHubId + "\"" : "") + "}";
        return given().contentType("application/json").body(body)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201).extract().path("peer.id");
    }

    @Test
    void anEdgePeersConfTrustsItsEdgesKeyAndNotTheHubs() {
        String edgeId = createEdge();
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-k\",\"assignedIp\":\"" + nextIp()
                        + "\",\"type\":\"client\",\"edgeHubId\":\"" + edgeId + "\"}")
                .when().post("/api/v1/users/" + createUser() + "/peers")
                .then().statusCode(201)
                .body("conf", containsString("PublicKey = " + EDGE_KEY))
                .body("conf", not(containsString(centralKey)));
    }

    @Test
    void anEdgePeersConfDialsItsEdgesEndpoint() {
        String edgeId = createEdge();
        String peerId = createPeer(createUser(), edgeId);
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", containsString("Endpoint = " + EDGE_ENDPOINT))
                .body("conf", not(containsString(centralEndpoint)));
    }

    @Test
    void aPeerAtTheCentralHubIsUnchanged() {
        createEdge();   // exists, but this peer is not assigned to it
        String peerId = createPeer(createUser(), null);
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", containsString("PublicKey = " + centralKey))
                .body("conf", containsString("Endpoint = " + centralEndpoint))
                .body("conf", containsString(SUPERNET));
    }

    @Test
    void anEdgePeerRoutesItsEdgesSitesAndNoOthers() {
        String edgeId = createEdge();
        createSiteBehind(EDGE_SITE_CIDR, edgeId);
        createSiteBehind(CENTRAL_SITE_CIDR, null);
        String peerId = createPeer(createUser(), edgeId);
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", containsString(EDGE_SITE_CIDR))
                .body("conf", not(containsString(CENTRAL_SITE_CIDR)));
    }

    /**
     * The supernet is a single CIDR declared to cover every site network. At an
     * edge it would route the other hubs' sites into a tunnel that cannot
     * deliver them, so it is dropped and this edge's sites are listed instead.
     * Without this the test above would pass and the device would still be
     * unable to reach the central sites — silently, via the supernet route.
     */
    @Test
    void anEdgePeerDoesNotGetTheSupernetThatWouldBlackholeTheOtherHubsSites() {
        String edgeId = createEdge();
        createSiteBehind(EDGE_SITE_CIDR, edgeId);
        String peerId = createPeer(createUser(), edgeId);
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("conf", not(containsString(SUPERNET)))
                .body("conf", containsString(EDGE_SITE_CIDR));
    }
}
