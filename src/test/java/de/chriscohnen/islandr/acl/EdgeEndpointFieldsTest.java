package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
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
import static org.hamcrest.Matchers.nullValue;

/**
 * edge-endpoint-fields (ADR-I-0003): an edge hub is described by the same two
 * things a hub is — what clients dial, and whose key they trust.
 *
 * <p>Both are optional on the edge itself, because an edge is usually created
 * (name, coordinates) before the machine behind it exists. They become
 * required at the moment they are first needed: assigning a peer to an edge
 * that cannot yet serve it would hand the admin a device whose configuration
 * cannot be rendered, and the error would surface somewhere else entirely.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EdgeEndpointFieldsTest {

    private static final String MARK = "edgefields";
    private static final String KEY = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        Peer.delete("edgeHubId in (select e.id from EdgeHub e where e.name like ?1)", MARK + "-%");
        Peer.delete("name like ?1", MARK + "-%");
        EdgeHub.delete("name like ?1", MARK + "-%");
        User.delete("email like ?1", MARK + "-%");
        // Precondition, not the subject — see EdgeHubsToggleTest.
        de.chriscohnen.islandr.settings.Settings s =
                de.chriscohnen.islandr.settings.Settings.findById(
                        de.chriscohnen.islandr.settings.Settings.SINGLETON_ID);
        if (s != null) s.edgeHubsEnabled = true;
    }

    @Transactional
    String createUser() {
        User u = User.createNew("Edge Fields", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.persist();
        return u.id;
    }

    private String createEdge(String endpoint, String publicKey) {
        StringBuilder b = new StringBuilder("{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\"");
        b.append(",\"enabled\":true,\"lat\":-33.45,\"lng\":-70.67");
        if (endpoint != null) b.append(",\"endpoint\":\"").append(endpoint).append("\"");
        if (publicKey != null) b.append(",\"serverPublicKey\":\"").append(publicKey).append("\"");
        return given().contentType("application/json").body(b.append("}").toString())
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");
    }

    /** Own block, like PeerHubAssignmentTest — the allocator hands out the low
     *  addresses other classes hardcode. */
    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(171);

    private String nextIp() {
        return "10.8.0." + host.getAndIncrement();
    }

    @Test
    void anEdgeCanBeCreatedBeforeItsWireguardSideExists() {
        String id = createEdge(null, null);
        given().when().get("/api/v1/edge-hubs/" + id)
                .then().statusCode(200)
                .body("endpoint", nullValue())
                .body("serverPublicKey", nullValue());
    }

    @Test
    void endpointAndKeyAreStoredAndReturned() {
        String id = createEdge("edge.santiago.example:51820", KEY);
        given().when().get("/api/v1/edge-hubs/" + id)
                .then().statusCode(200)
                .body("endpoint", is("edge.santiago.example:51820"))
                .body("serverPublicKey", is(KEY));
    }

    @Test
    void aMalformedServerKeyIsRefused() {
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-bad\",\"enabled\":true,\"lat\":0,\"lng\":0,"
                        + "\"serverPublicKey\":\"not-a-wireguard-key\"}")
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(400);
    }

    @Test
    void aPeerCannotBeAssignedToAnEdgeThatCannotServeItYet() {
        String userId = createUser();
        String edgeId = createEdge(null, null);
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-p1\",\"assignedIp\":\"" + nextIp() + "\","
                        + "\"type\":\"client\",\"edgeHubId\":\"" + edgeId + "\"}")
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400)
                .body(containsString("endpoint"));
    }

    @Test
    void aPeerCanBeAssignedOnceTheEdgeIsConfigured() {
        String userId = createUser();
        String edgeId = createEdge("edge.santiago.example:51820", KEY);
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-p2\",\"assignedIp\":\"" + nextIp() + "\","
                        + "\"type\":\"client\",\"edgeHubId\":\"" + edgeId + "\"}")
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.edgeHubId", is(edgeId));
    }
}
