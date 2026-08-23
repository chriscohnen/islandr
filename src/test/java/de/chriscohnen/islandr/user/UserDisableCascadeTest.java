package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Disabling a user used to only block their portal/OIDC login — their
 * already-configured WireGuard peers kept working until someone separately
 * disabled each one. That's the wrong default for anyone locking an account
 * (e.g. an IdM-driven deprovisioning flow expecting "disabled" to mean no
 * network access, not just no new login). {@code PUT /users/{id}/enabled}
 * now cascades to every one of that user's peers.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class UserDisableCascadeTest {

    private String createUser() {
        return given().contentType("application/json")
                .body("""
                        { "name": "Cascade Test", "email": "cascade-%s@example.com" }
                        """.formatted(UUID.randomUUID()))
                .when().post("/api/v1/users")
                .then().statusCode(201)
                .extract().path("id");
    }

    // Asks the server for a free IP rather than hardcoding one — the peers
    // table's assignedIp is unique across the whole test suite's shared
    // in-memory DB (see application.properties' %test datasource), and a
    // literal like "10.8.0.40" collides with half a dozen other test
    // classes' own fixtures depending on run order.
    private String nextIp() {
        return given().when().get("/api/v1/peers/next-ip")
                .then().statusCode(200)
                .extract().path("assignedIp");
    }

    private String createPeer(String userId, String name) {
        return given().contentType("application/json")
                .body("""
                        { "name": "%s", "assignedIp": "%s" }
                        """.formatted(name, nextIp()))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");
    }

    @Test
    void disablingUser_disablesAllTheirPeers() {
        String userId = createUser();
        String peer1 = createPeer(userId, "laptop");
        String peer2 = createPeer(userId, "phone");

        given().contentType("application/json")
                .body("{ \"enabled\": false }")
                .when().put("/api/v1/users/" + userId + "/enabled")
                .then().statusCode(200)
                .body("enabled", equalTo(false));

        given().when().get("/api/v1/peers/" + peer1)
                .then().statusCode(200)
                .body("enabled", equalTo(false));
        given().when().get("/api/v1/peers/" + peer2)
                .then().statusCode(200)
                .body("enabled", equalTo(false));
    }

    /**
     * The cascade must mark the source "manual" (the same value a direct
     * per-peer disable uses) — not some new source — because
     * PeerScheduleJob only leaves "manual"-sourced peers alone; anything
     * else is fair game for the scheduler to flip back on at its next
     * window transition. A locked-out user's peer re-enabling itself on
     * the next schedule tick would defeat the whole point of this cascade.
     */
    @Test
    void cascadeDisable_marksSourceManual_soScheduleCannotReEnableIt() {
        String userId = createUser();
        String peerId = createPeer(userId, "scheduled-laptop");

        given().contentType("application/json")
                .body("{ \"enabled\": false }")
                .when().put("/api/v1/users/" + userId + "/enabled")
                .then().statusCode(200);

        given().when().get("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("enabledSource", equalTo("manual"));
    }

    /**
     * Re-enabling the user must NOT resurrect their peers automatically —
     * some of those may have been disabled for unrelated reasons before the
     * user was ever locked. Reactivating access stays a deliberate,
     * separate admin action.
     */
    @Test
    void reEnablingUser_doesNotReEnableTheirPeers() {
        String userId = createUser();
        String peerId = createPeer(userId, "re-enable-test");

        given().contentType("application/json").body("{ \"enabled\": false }")
                .when().put("/api/v1/users/" + userId + "/enabled")
                .then().statusCode(200);

        given().contentType("application/json").body("{ \"enabled\": true }")
                .when().put("/api/v1/users/" + userId + "/enabled")
                .then().statusCode(200)
                .body("enabled", equalTo(true));

        given().when().get("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("enabled", equalTo(false));
    }

    @Test
    void disablingAlreadyDisabledPeer_isANoOp_notAnError() {
        String userId = createUser();
        String peerId = createPeer(userId, "already-off");

        given().contentType("application/json").body("{ \"enabled\": false }")
                .when().put("/api/v1/peers/" + peerId + "/enabled")
                .then().statusCode(200);

        given().contentType("application/json").body("{ \"enabled\": false }")
                .when().put("/api/v1/users/" + userId + "/enabled")
                .then().statusCode(200);

        given().when().get("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("enabled", equalTo(false));
    }
}
