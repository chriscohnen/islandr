package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A peer's owning user is changeable after creation.
 *
 * <p>The order of work on a fresh hub is import-then-invite: an admin pulls the
 * existing peers off the wg interface first and creates the user accounts
 * afterwards, so every imported client starts out owned by nobody. Until this
 * existed the only route to an owner was delete-and-recreate, which throws away
 * the peer's activity history and hands the user a new key.
 *
 * <p>{@code userId} follows the same omitted-means-unchanged rule as
 * {@code type}, not the omitted-means-cleared rule of the other nullable
 * fields: {@code RuleBuilder} derives every ACL grant from this field, so a
 * request that simply forgot to mention it must not silently strip a client of
 * all its access.
 */
@QuarkusTest
class PeerUserReassignTest {

    @Inject PeerService peers;

    @Transactional
    User newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = User.createNew("Reassign " + tag, "reassign-" + tag + "@example.test");
        u.persist();
        return u;
    }

    private Peer imported(String type, String cidrs) {
        String pk = "REASSIGN" + UUID.randomUUID().toString().replace("-", "").substring(0, 18) + "AAAAAAAAAAAAAAAA=";
        peers.wgImport(List.of(new PeerDto.WgImportEntry(pk, "p-" + pk.substring(8, 14),
                nextFreeIp(), null, type, cidrs)));
        return Peer.find("publicKey", pk).firstResult();
    }

    private static String nextFreeIp() {
        for (int i = 100; i < 250; i++) {
            String ip = "10.8.0." + i;
            if (Peer.find("assignedIp", ip).count() == 0) return ip;
        }
        throw new IllegalStateException("test subnet exhausted");
    }

    /** Re-read from the database, not the session — see PeerTypeChangeTest#reload. */
    private Peer reload(String id) {
        Peer.getEntityManager().clear();
        return Peer.findById(id);
    }

    private PeerDto.UpdateRequest req(Peer p, String type, String cidrs, String userId) {
        return new PeerDto.UpdateRequest(p.name, p.assignedIp, p.assignedIpv6, cidrs,
                null, null, null, null, null, null, null, null, null, type, userId);
    }

    @Test
    void unassignedPeerGetsAUser() {
        Peer p = imported("client", null);
        assertThat(p.userId).isNull();
        User u = newUser();

        peers.update(p.id, req(p, null, null, u.id));

        assertThat(reload(p.id).userId).isEqualTo(u.id);
    }

    @Test
    void peerMovesToAnotherUser() {
        Peer p = imported("client", null);
        User first = newUser();
        User second = newUser();
        peers.update(p.id, req(p, null, null, first.id));

        peers.update(p.id, req(p, null, null, second.id));

        assertThat(reload(p.id).userId).isEqualTo(second.id);
    }

    @Test
    void blankUserId_unassignsThePeer() {
        Peer p = imported("client", null);
        User u = newUser();
        peers.update(p.id, req(p, null, null, u.id));

        peers.update(p.id, req(p, null, null, ""));

        assertThat(reload(p.id).userId).isNull();
    }

    @Test
    void omittedUserId_leavesTheAssignmentAlone() {
        Peer p = imported("client", null);
        User u = newUser();
        peers.update(p.id, req(p, null, null, u.id));

        peers.update(p.id, req(p, null, null, null));

        assertThat(reload(p.id).userId).isEqualTo(u.id);
    }

    @Test
    void unknownUser_isRejected() {
        Peer p = imported("client", null);

        assertThatThrownBy(() -> peers.update(p.id, req(p, null, null, "no-such-user")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void assigningAUserToASitePeer_isRejected() {
        Peer p = imported("site", "192.168.91.0/24");
        User u = newUser();

        assertThatThrownBy(() -> peers.update(p.id, req(p, null, "192.168.91.0/24", u.id)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void switchingToSiteWhileAssigningAUser_isRejected() {
        Peer p = imported("client", null);
        User u = newUser();

        assertThatThrownBy(() -> peers.update(p.id, req(p, "site", "192.168.92.0/24", u.id)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("userId");
    }
}
