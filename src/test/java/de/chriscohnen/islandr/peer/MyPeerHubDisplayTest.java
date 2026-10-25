package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * portal-shows-device-hub: a portal user picks a location when they add a
 * device, and until now never saw it again.
 *
 * <p>The name is resolved on the server, which looks like indirection and is
 * not: {@code GET /peers/mine/edge-options} lists only the edges a user can
 * still reach, and an admin may well have assigned a peer to one they cannot
 * — or the grant that gave them reach may since be gone. The browser would
 * then hold an id and no name for the one device it most needs to label.
 *
 * <p>Nothing is said while the feature is switched off, so an installation
 * that once had edge hubs does not keep mentioning a concept its operator
 * has turned off.
 */
@QuarkusTest
class MyPeerHubDisplayTest {

    private static final String MARK = "hubdisplay";

    @Inject SessionService sessions;

    @BeforeEach
    void noLeakedAdminSpec() {
        RestAssured.requestSpecification = null;
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
        if (s != null) s.edgeHubsEnabled = false;
    }

    @Transactional
    void setEdgeHubsEnabled(boolean on) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) s.edgeHubsEnabled = on;
    }

    @Transactional
    String createEdge(String locationLabel) {
        EdgeHub e = EdgeHub.createNew(MARK + "-" + UUID.randomUUID(), -33.45, -70.67, locationLabel);
        e.endpoint = "edge.example:51820";
        e.serverPublicKey = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";
        e.persist();
        return e.id;
    }

    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(191);

    /** Own address block, like the other classes here — the allocator hands
     *  out the low addresses that others hardcode. */
    @Transactional
    String sessionWithPeerAt(String edgeHubId) {
        User u = User.createNew("Hub Display", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.isAdmin = false;
        u.persist();
        Peer p = Peer.createNew(u.id, MARK + "-" + UUID.randomUUID(),
                "k" + UUID.randomUUID(), "10.8.0." + host.getAndIncrement());
        p.edgeHubId = edgeHubId;
        p.persist();
        return sessions.create(Session.MICROSOFT, "h-" + u.id.substring(0, 6), u.id).id;
    }

    @Test
    void anEdgePeerCarriesItsEdgesLabel() {
        setEdgeHubsEnabled(true);
        String cookie = sessionWithPeerAt(createEdge("Santiago"));
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].edgeHubName", is("Santiago"));
    }

    /** The label is optional on an edge, the name is not — so the name is what
     *  the portal falls back to, never the id. */
    @Test
    void anEdgeWithoutALabelFallsBackToItsName() {
        setEdgeHubsEnabled(true);
        String edgeId = createEdge(null);
        String cookie = sessionWithPeerAt(edgeId);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].edgeHubName", org.hamcrest.Matchers.startsWith(MARK + "-"))
                .body("[0].edgeHubName", org.hamcrest.Matchers.not(is(edgeId)));
    }

    @Test
    void aPeerAtTheCentralHubCarriesNoName() {
        setEdgeHubsEnabled(true);
        String cookie = sessionWithPeerAt(null);
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].edgeHubId", nullValue())
                .body("[0].edgeHubName", nullValue());
    }

    @Test
    void nothingIsSaidWhileEdgeHubsAreSwitchedOff() {
        String cookie = sessionWithPeerAt(createEdge("Santiago"));
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].edgeHubName", nullValue());
    }
}
