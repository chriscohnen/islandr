package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deprovisioning over the external API. The assertion that matters is not the
 * status code but the state afterwards: an identity provider disabling someone
 * only stops their next login, while the device they already carry keeps
 * tunnelling until the peer goes down too.
 */
@QuarkusTest
class UserExternalDisableTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        de.chriscohnen.islandr.apikey.ApiKey.deleteAll();
        Peer.deleteAll();
        User.deleteAll();
    }

    @Test
    void disablingAUserAlsoTakesTheirPeersDown() {
        String key = apiKeys.create("deprovision", "admin").rawKey();
        String userId = createUser(true);
        String peerId = createPeer(userId, "10.9.1.10", true);

        given().header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .body("{\"enabled\": false}")
                .when().put("/api/external/v1/users/" + userId + "/enabled")
                .then().statusCode(200);

        assertThat(userEnabled(userId)).isFalse();
        assertThat(peerEnabled(peerId))
                .as("a disabled user must not keep a working tunnel")
                .isFalse();
    }

    @Test
    void reEnablingAUserDoesNotBringTheirPeersBack() {
        String key = apiKeys.create("deprovision", "admin").rawKey();
        String userId = createUser(true);
        String peerId = createPeer(userId, "10.9.1.11", true);

        setEnabled(key, userId, false);
        setEnabled(key, userId, true);

        assertThat(userEnabled(userId)).isTrue();
        assertThat(peerEnabled(peerId))
                .as("deciding a device may reconnect stays an explicit, per-peer action")
                .isFalse();
    }

    @Test
    void aSinglePeerCanBeDisabledWithoutTouchingItsOwner() {
        String key = apiKeys.create("deprovision", "admin").rawKey();
        String userId = createUser(true);
        String lost = createPeer(userId, "10.9.1.12", true);
        String kept = createPeer(userId, "10.9.1.13", true);

        given().header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .body("{\"enabled\": false}")
                .when().put("/api/external/v1/peers/" + lost + "/enabled")
                .then().statusCode(200);

        assertThat(peerEnabled(lost)).isFalse();
        assertThat(peerEnabled(kept)).isTrue();
        assertThat(userEnabled(userId)).isTrue();
    }

    @Test
    void noCredentials_rejected() {
        String userId = createUser(true);
        given().contentType("application/json").body("{\"enabled\": false}")
                .when().put("/api/external/v1/users/" + userId + "/enabled")
                .then().statusCode(401);
    }

    @Test
    void unknownUser_notFound() {
        String key = apiKeys.create("deprovision", "admin").rawKey();
        given().header("Authorization", "Bearer " + key)
                .contentType("application/json").body("{\"enabled\": false}")
                .when().put("/api/external/v1/users/" + UUID.randomUUID() + "/enabled")
                .then().statusCode(404);
    }

    private void setEnabled(String key, String userId, boolean enabled) {
        given().header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .body("{\"enabled\": " + enabled + "}")
                .when().put("/api/external/v1/users/" + userId + "/enabled")
                .then().statusCode(200);
    }

    @Transactional
    String createUser(boolean enabled) {
        User u = new User();
        u.id = UUID.randomUUID().toString();
        u.name = "ext-" + u.id.substring(0, 8);
        u.email = u.name + "@example.test";
        u.enabled = enabled;
        u.createdAt = Instant.now();
        u.persist();
        return u.id;
    }

    @Transactional
    String createPeer(String userId, String ip, boolean enabled) {
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = userId;
        p.name = "peer-" + ip;
        p.type = "client";
        p.publicKey = "EXT" + UUID.randomUUID().toString().replace("-", "") + "==";
        p.assignedIp = ip;
        p.enabled = enabled;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p.id;
    }

    @Transactional
    boolean userEnabled(String id) { return User.<User>findById(id).enabled; }

    @Transactional
    boolean peerEnabled(String id) { return Peer.<Peer>findById(id).enabled; }
}
