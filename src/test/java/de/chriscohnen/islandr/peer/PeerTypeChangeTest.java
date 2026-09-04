package de.chriscohnen.islandr.peer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A peer's type is changeable after creation. An imported peer arrives as a
 * client when nobody told the import otherwise, and the mistake used to be
 * unfixable in the UI — delete and recreate was the only route, which loses the
 * peer's activity history.
 */
@QuarkusTest
class PeerTypeChangeTest {

    @Inject PeerService peers;

    private Peer imported(String type, String cidrs) {
        String pk = "TYPECHG" + UUID.randomUUID().toString().replace("-", "").substring(0, 19) + "AAAAAAAAAAAAAAAA=";
        peers.wgImport(List.of(new PeerDto.WgImportEntry(pk, "p-" + pk.substring(7, 13),
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

    /**
     * Re-read from the database, not from the session. The test method holds one
     * Hibernate session across all its Panache calls, so a plain findById after
     * the service's own transaction would hand back the instance loaded before
     * the update and silently assert against stale state.
     */
    private Peer reload(String id) {
        Peer.getEntityManager().clear();
        return Peer.findById(id);
    }

    private PeerDto.UpdateRequest req(Peer p, String type, String cidrs) {
        return new PeerDto.UpdateRequest(p.name, p.assignedIp, p.assignedIpv6, cidrs,
                null, null, null, null, null, null, null, null, null, type);
    }

    @Test
    void clientBecomesSite_whenRoutedCidrsAreSupplied() {
        Peer p = imported("client", null);

        peers.update(p.id, req(p, "site", "192.168.88.0/24"));

        Peer after = reload(p.id);
        assertThat(after.isSite()).isTrue();
        assertThat(after.siteAllowedCidrs).isEqualTo("192.168.88.0/24");
        assertThat(after.deviceType).isNull();
    }

    @Test
    void clientBecomesSite_withoutCidrs_isRejected() {
        Peer p = imported("client", null);

        assertThatThrownBy(() -> peers.update(p.id, req(p, "site", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("siteAllowedCidrs");
    }

    @Test
    void siteBecomesClient_clearsRoutedCidrsAndGeo() {
        Peer p = imported("site", "192.168.89.0/24");

        peers.update(p.id, req(p, "client", null));

        Peer after = reload(p.id);
        assertThat(after.isSite()).isFalse();
        assertThat(after.siteAllowedCidrs).isNull();
        assertThat(after.lat).isNull();
        assertThat(after.lng).isNull();
    }

    @Test
    void omittedType_leavesTheTypeAlone() {
        Peer p = imported("site", "192.168.90.0/24");

        peers.update(p.id, req(p, null, "192.168.90.0/24"));

        assertThat(reload(p.id).isSite()).isTrue();
    }

    @Test
    void unknownType_isRejected() {
        Peer p = imported("client", null);

        assertThatThrownBy(() -> peers.update(p.id, req(p, "gateway", null)))
                .isInstanceOf(BadRequestException.class);
    }
}
