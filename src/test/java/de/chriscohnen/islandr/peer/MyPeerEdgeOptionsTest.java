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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * Which locations the portal offers when someone adds a device, and — the part
 * that matters — which it accepts.
 *
 * <p>The rule is reach, not existence: a device at a location where its owner
 * may not touch anything is a tunnel to nowhere (decision of 2026-10-02). The
 * portal hides what it must not offer, and the server refuses it regardless,
 * because the hiding is a convenience and the refusal is the rule.
 *
 * <p>The positive case — a user who <i>does</i> have reach sees the edge — is
 * covered one level down in {@code EdgeReachableHubsTest}, which exercises
 * {@code reachableEdgeHubIdsForUser} against every grant type. Repeating that setup
 * here would test the same resolver twice and the endpoint not at all.
 */
@QuarkusTest
class MyPeerEdgeOptionsTest {

    private static final String MARK = "edgeoptions";

    @Inject SessionService sessions;

    @BeforeEach
    void noLeakedAdminSpec() {
        RestAssured.requestSpecification = null;
    }

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        Peer.delete("name like ?1", MARK + "-%");
        EdgeHub.delete("name like ?1", MARK + "-%");
        User.delete("email like ?1", MARK + "-%");
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) s.edgeHubsEnabled = false;
    }

    @Transactional
    void setEdgeHubsEnabled(boolean on) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) {
            s.edgeHubsEnabled = on;
            // Settings is one shared row for the whole suite, and
            // SettingsResourceTest's partial PUT leaves self-service off
            // behind it (Jackson defaults an omitted boolean to false). Forced
            // back on so this class does not depend on execution order — same
            // workaround, same reason as MyPeerResourceCreateMtuTest.
            s.selfServicePeerCreation = true;
        }
    }

    @Transactional
    String createEdge() {
        EdgeHub e = new EdgeHub();
        e.id = UUID.randomUUID().toString();
        e.name = MARK + "-" + UUID.randomUUID();
        e.enabled = true;
        e.lat = -33.45;
        e.lng = -70.67;
        e.endpoint = "edge.example:51820";
        e.serverPublicKey = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";
        e.createdAt = java.time.Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
        return e.id;
    }

    @Transactional
    String orgUserSession() {
        User u = User.createNew("Portal User", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.isAdmin = false;
        u.persist();
        return sessions.create(Session.MICROSOFT, "p-" + u.id.substring(0, 6), u.id).id;
    }

    @Test
    void nothingIsOfferedWhileEdgeHubsAreSwitchedOff() {
        String cookie = orgUserSession();
        createEdge();   // exists, but the installation does not use the concept
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine/edge-options")
                .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void nothingIsOfferedToSomeoneWhoCannotReachAnythingThere() {
        setEdgeHubsEnabled(true);
        String cookie = orgUserSession();
        createEdge();   // no grants for this user anywhere
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine/edge-options")
                .then().statusCode(200).body("$", hasSize(0));
    }

    /** The one that would be a hole: not offering it is cosmetic, refusing it
     *  is the rule. A portal user posting an edge id by hand must not get a
     *  device at a location they have no business being at. */
    @Test
    void anEdgeThatWasNotOfferedIsRefusedOnCreate() {
        setEdgeHubsEnabled(true);
        String cookie = orgUserSession();
        String edgeId = createEdge();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("{\"name\":\"" + MARK + "-sneaky\",\"deviceType\":\"laptop\","
                        + "\"edgeHubId\":\"" + edgeId + "\"}")
                .when().post("/api/v1/peers/mine")
                .then().statusCode(400)
                .body(containsString("not available to you"));
    }
}
