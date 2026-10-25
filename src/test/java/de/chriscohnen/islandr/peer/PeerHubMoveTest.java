package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.MockWgAdapter;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * peer-hub-move-to-edge: a peer at the central hub can be moved to an edge.
 * Nothing else can move.
 *
 * <p>ADR-I-0003 made the assignment immutable, with the reasoning that a move
 * shifts an address between two routing domains while both sides still hold
 * state about it. That is true in general and <b>not</b> true in this one
 * direction: the old side is the central hub's own interface, and the hub can
 * take the key off it. Afterwards no key works at two hubs, which is the
 * invariant the ADR actually cares about.
 *
 * <p>Every other direction stays closed, and for a reason that has nothing to
 * do with routing: until an edge fetches its configuration from the hub, the
 * hub cannot revoke anything there. A move away from an edge would leave a
 * working tunnel that Islandr believes it has taken away — the worst kind of
 * wrong, because the UI would say it succeeded.
 *
 * <p>The key pair is deliberately <b>not</b> rotated. There is no stale trust
 * to cut, the device may have imported its own key (which the hub could not
 * replace anyway), and the one thing that genuinely changes — the whole
 * {@code [Peer]} block — is re-rendered on the next config download.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerHubMoveTest {

    private static final String MARK = "hubmove";
    private static final String KEY = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";

    @Inject WgAdapter wg;

    private MockWgAdapter mock() {
        return (MockWgAdapter) io.quarkus.arc.ClientProxy.unwrap(wg);
    }

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

    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(221);

    private String nextIp() {
        return "10.8.0." + host.getAndIncrement();
    }

    @Transactional
    String createUser() {
        User u = User.createNew("Hub Move", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.persist();
        return u.id;
    }

    private String createEdge(boolean ready) {
        StringBuilder b = new StringBuilder("{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\"");
        b.append(",\"enabled\":true,\"lat\":-33.45,\"lng\":-70.67");
        if (ready) b.append(",\"endpoint\":\"edge.example:51820\",\"serverPublicKey\":\"").append(KEY).append("\"");
        return given().contentType("application/json").body(b.append("}").toString())
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");
    }

    private String createPeer(String edgeHubId) {
        String body = "{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\",\"assignedIp\":\"" + nextIp()
                + "\",\"type\":\"client\""
                + (edgeHubId != null ? ",\"edgeHubId\":\"" + edgeHubId + "\"" : "") + "}";
        return given().contentType("application/json").body(body)
                .when().post("/api/v1/users/" + createUser() + "/peers")
                .then().statusCode(201).extract().path("peer.id");
    }

    private io.restassured.response.ValidatableResponse move(String peerId, String target) {
        return given().contentType("application/json")
                .body(target == null ? "{}" : "{\"edgeHubId\":\"" + target + "\"}")
                .when().post("/api/v1/peers/" + peerId + "/hub")
                .then();
    }

    @Test
    void aCentralPeerCanBeMovedToAnEdge() {
        String edgeId = createEdge(true);
        String peerId = createPeer(null);
        move(peerId, edgeId).statusCode(200).body("edgeHubId", is(edgeId));
        given().when().get("/api/v1/peers/" + peerId)
                .then().statusCode(200).body("edgeHubId", is(edgeId));
    }

    /** The whole reason this direction is safe: the hub owns the interface the
     *  peer is leaving, so it can actually take the key off it. */
    @Test
    void theKeyIsTakenOffTheCentralInterface() {
        String edgeId = createEdge(true);
        String peerId = createPeer(null);
        String key = given().when().get("/api/v1/peers/" + peerId)
                .then().extract().path("publicKey");
        assertThat(mock().showPeers("wg0").stream().map(WgAdapter.PeerStatus::publicKey).toList())
                .as("a central peer is on the hub's interface to begin with")
                .contains(key);

        move(peerId, edgeId).statusCode(200);

        assertThat(mock().showPeers("wg0").stream().map(WgAdapter.PeerStatus::publicKey).toList())
                .as("after the move it must not be trusted centrally any more")
                .doesNotContain(key);
    }

    @Test
    void aPeerAtAnEdgeCannotBeMovedBackToTheCentralHub() {
        String peerId = createPeer(createEdge(true));
        move(peerId, null).statusCode(409).body(containsString("cannot revoke"));
    }

    @Test
    void aPeerCannotBeMovedBetweenTwoEdges() {
        String peerId = createPeer(createEdge(true));
        move(peerId, createEdge(true)).statusCode(409).body(containsString("cannot revoke"));
    }

    @Test
    void anEdgeThatCannotServePeersYetIsRefused() {
        String peerId = createPeer(null);
        move(peerId, createEdge(false)).statusCode(400).body(containsString("endpoint"));
    }

    @Test
    void anUnknownEdgeIsRefused() {
        String peerId = createPeer(null);
        move(peerId, UUID.randomUUID().toString()).statusCode(400);
    }
}
