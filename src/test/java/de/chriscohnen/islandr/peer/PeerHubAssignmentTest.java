package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
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
import static org.hamcrest.Matchers.nullValue;

/**
 * peer-hub-assignment (ADR-I-0003): a peer belongs to exactly one hub, named
 * at creation and not changed afterwards.
 *
 * <p>The tests are grouped around the three properties the decision rests on,
 * because each of them is a way the old derived model went wrong:
 *
 * <ol>
 *   <li><b>Exactly one.</b> {@code null} means the central hub; a value names
 *       an edge. There is no state in which a peer answers "both".</li>
 *   <li><b>Not changeable.</b> Moving the assignment would move an address
 *       between two routing domains while both sides still hold state about
 *       it. The API says so instead of doing it.</li>
 *   <li><b>No back door.</b> Deleting an edge must not silently re-home its
 *       peers to the central hub — that would be the move of property 2,
 *       performed by a different endpoint.</li>
 * </ol>
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerHubAssignmentTest {

    /** Everything this class creates carries one of these prefixes, and only
     *  those rows are removed again. A blanket {@code deleteAll()} is wrong
     *  here twice over: {@code sites} is referenced by resources and grants
     *  other test classes leave behind (the delete fails on a foreign key),
     *  and {@code users} holds the account this class's own admin session is
     *  authenticated as. */
    private static final String MARK = "hubassign";

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        // Peers zuerst, und nicht nur nach Namen: zwei Tests benennen den
        // Peer um, der Name taugt danach nicht mehr als Merkmal. Die
        // Edge-Zuordnung bleibt dagegen (sie ist unveränderlich) — und der
        // neue Fremdschlüssel lässt ein Edge mit Peers ohnehin nicht löschen.
        Peer.delete("edgeHubId in (select e.id from EdgeHub e where e.name like ?1)", MARK + "-%");
        Peer.delete("name like ?1", MARK + "-%");
        EdgeHub.delete("name like ?1", MARK + "-%");
        User.delete("email like ?1", MARK + "-%");
    }

    @Transactional
    String createEdge() {
        EdgeHub e = new EdgeHub();
        e.id = UUID.randomUUID().toString();
        e.name = MARK + "-" + UUID.randomUUID();
        e.enabled = true;
        e.lat = -33.45;
        e.lng = -70.67;
        // Assignment requires an edge that can actually serve a peer
        // (edge-endpoint-fields) — the refusal when it cannot is covered in
        // EdgeEndpointFieldsTest, not here.
        e.endpoint = "edge.example:51820";
        e.serverPublicKey = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";
        e.createdAt = java.time.Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
        return e.id;
    }

    @Transactional
    String createUser() {
        User u = User.createNew("Hub Assign", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.persist();
        return u.id;
    }

    /** A block of addresses no other test class uses (checked across
     *  src/test). Deliberately not {@code GET /peers/next-ip}: the allocator
     *  hands out the lowest free address, which is exactly what the other
     *  classes hardcode — taking those from under them turns this class into
     *  a source of unique-constraint failures elsewhere. */
    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(153);

    private String nextIp() {
        return "10.8.0." + host.getAndIncrement();
    }

    private static String createBody(String ip, String type, String edgeHubId) {
        StringBuilder sb = new StringBuilder("{\"name\":\"" + MARK + "-" + ip.replace('.', '-') + "\"");
        sb.append(",\"assignedIp\":\"").append(ip).append("\"");
        if (type != null) sb.append(",\"type\":\"").append(type).append("\"");
        if (type != null && type.equals("site")) sb.append(",\"siteAllowedCidrs\":\"10.20.0.0/24\"");
        if (edgeHubId != null) sb.append(",\"edgeHubId\":\"").append(edgeHubId).append("\"");
        return sb.append("}").toString();
    }

    // ── 1. Exactly one hub ────────────────────────────────────────────────

    @Test
    void aPeerCreatedWithoutAnEdgeBelongsToTheCentralHub() {
        String ip = nextIp();
        String userId = createUser();
        String id = given().contentType("application/json").body(createBody(ip, null, null))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.edgeHubId", nullValue())
                .extract().path("peer.id");

        assertThat(findEdgeHubId(id)).isNull();
    }

    @Test
    void aPeerCreatedWithAnEdgeBelongsToThatEdge() {
        String ip = nextIp();
        String userId = createUser();
        String edgeId = createEdge();
        String id = given().contentType("application/json").body(createBody(ip, null, edgeId))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.edgeHubId", is(edgeId))
                .extract().path("peer.id");

        assertThat(findEdgeHubId(id)).isEqualTo(edgeId);
    }

    @Test
    void anUnknownEdgeIsRefused() {
        String ip = nextIp();
        String userId = createUser();
        given().contentType("application/json")
                .body(createBody(ip, null, UUID.randomUUID().toString()))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400)
                .body(containsString("edge hub not found"));
    }

    /** A site gateway peer is the far end of a site-to-site link to the hub it
     *  dials. Assigning it to an edge would claim the edge renders site-to-site
     *  rules, which it does not — so the request is refused rather than
     *  quietly stored. */
    @Test
    void aSitePeerCannotBeAssignedToAnEdge() {
        String ip = nextIp();
        String edgeId = createEdge();
        given().contentType("application/json").body(createBody(ip, "site", edgeId))
                .when().post("/api/v1/peers")
                .then().statusCode(400)
                .body(containsString("only meaningful for client peers"));
    }

    // ── 2. Not changeable ─────────────────────────────────────────────────

    @Test
    void theAssignmentCannotBeChangedByAnUpdate() {
        String ip = nextIp();
        String userId = createUser();
        String edgeId = createEdge();
        String otherEdge = createEdge();
        String id = given().contentType("application/json").body(createBody(ip, null, edgeId))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201).extract().path("peer.id");

        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-moved\",\"assignedIp\":\"" + ip + "\",\"edgeHubId\":\"" + otherEdge + "\"}")
                .when().put("/api/v1/peers/" + id)
                .then().statusCode(409)
                .body(containsString("cannot be changed after it is created"));

        assertThat(findEdgeHubId(id)).isEqualTo(edgeId);
    }

    @Test
    void anUpdateThatRepeatsTheSameAssignmentIsAccepted() {
        String ip = nextIp();
        String userId = createUser();
        String edgeId = createEdge();
        String id = given().contentType("application/json").body(createBody(ip, null, edgeId))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201).extract().path("peer.id");

        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-renamed\",\"assignedIp\":\"" + ip + "\",\"edgeHubId\":\"" + edgeId + "\"}")
                .when().put("/api/v1/peers/" + id)
                .then().statusCode(200)
                .body("peer.edgeHubId", is(edgeId));
    }

    /** Omitting the field leaves the assignment alone, deliberately unlike the
     *  other nullable fields on {@code UpdateRequest} (which clear). Clearing
     *  it would be a move to the central hub. */
    @Test
    void anUpdateOmittingTheFieldKeepsTheAssignment() {
        String ip = nextIp();
        String userId = createUser();
        String edgeId = createEdge();
        String id = given().contentType("application/json").body(createBody(ip, null, edgeId))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201).extract().path("peer.id");

        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-renamed\",\"assignedIp\":\"" + ip + "\"}")
                .when().put("/api/v1/peers/" + id)
                .then().statusCode(200);

        assertThat(findEdgeHubId(id)).isEqualTo(edgeId);
    }

    // ── 3. No back door ───────────────────────────────────────────────────

    @Test
    void anEdgeWithAssignedPeersCannotBeDeleted() {
        String ip = nextIp();
        String userId = createUser();
        String edgeId = createEdge();
        given().contentType("application/json").body(createBody(ip, null, edgeId))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201);

        given().when().delete("/api/v1/edge-hubs/" + edgeId)
                .then().statusCode(409)
                .body(containsString("still dial in at this edge hub"));
    }

    @Test
    void anEdgeWithoutAssignedPeersCanStillBeDeleted() {
        String edgeId = createEdge();
        given().when().delete("/api/v1/edge-hubs/" + edgeId).then().statusCode(204);
    }

    @Transactional
    String findEdgeHubId(String peerId) {
        Peer p = Peer.findById(peerId);
        return p == null ? null : p.edgeHubId;
    }
}
