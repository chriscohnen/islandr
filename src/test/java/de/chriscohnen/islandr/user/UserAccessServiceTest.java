package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cascade "withdrawing a user's access also takes their devices down" is a
 * rule about the domain, not about HTTP. It lived inside the admin REST
 * resource, where a second caller could only copy it — and where it had already
 * drifted: two of the three places that disable a user's peers recomputed the
 * ruleset afterwards, one did not.
 *
 * <p>These tests address the service directly, with no REST layer involved, so
 * the rule stays verifiable from every caller that needs it (#83 is what a
 * copied rule costs).
 */
@QuarkusTest
class UserAccessServiceTest {

    @Inject UserAccessService access;

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        Peer.deleteAll();
        User.deleteAll();
    }

    @Test
    void disablingAUserTakesTheirEnabledPeersDown() {
        String userId = createUser();
        String peerA = createPeer(userId, "10.9.0.21", true);
        String peerB = createPeer(userId, "10.9.0.22", true);

        int disabled = access.withdrawPeerAccess(userId);

        assertThat(disabled).isEqualTo(2);
        assertThat(enabledOf(peerA)).isFalse();
        assertThat(enabledOf(peerB)).isFalse();
    }

    @Test
    void peersAlreadyDownAreNotCountedAgain() {
        String userId = createUser();
        createPeer(userId, "10.9.0.23", false);

        assertThat(access.withdrawPeerAccess(userId))
                .as("nothing changed, so nothing to report — a re-run must stay silent")
                .isZero();
    }

    @Test
    void anotherUsersPeersAreUntouched() {
        String victim = createUser();
        String bystander = createUser();
        createPeer(victim, "10.9.0.24", true);
        String other = createPeer(bystander, "10.9.0.25", true);

        access.withdrawPeerAccess(victim);

        assertThat(enabledOf(other)).isTrue();
    }

    @Transactional
    String createUser() {
        User u = new User();
        u.id = UUID.randomUUID().toString();
        u.name = "user-" + u.id.substring(0, 8);
        u.email = u.name + "@example.test";
        u.enabled = true;
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
        p.publicKey = "TESTKEY" + UUID.randomUUID().toString().replace("-", "") + "=";
        p.assignedIp = ip;
        p.enabled = enabled;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p.id;
    }

    @Transactional
    boolean enabledOf(String peerId) {
        return Peer.<Peer>findById(peerId).enabled;
    }
}
