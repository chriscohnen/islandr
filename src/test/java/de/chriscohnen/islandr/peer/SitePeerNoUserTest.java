package de.chriscohnen.islandr.peer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Site peers have no owning user (commit 43faed0), so {@code peers.user_id} must
 * be nullable. Before V37 the column was NOT NULL since V2, so creating a site
 * peer failed at insert (the bug reported on 0.9.4/0.10.0). Reproduces the root
 * cause directly: persist a user-less peer and confirm it is listable (the
 * ruleset recompute lists peers, which is where the failure surfaced).
 */
@QuarkusTest
class SitePeerNoUserTest {

    @Test
    @Transactional
    void sitePeer_withNullUser_persistsAndIsListable() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Peer p = Peer.createNew(null, "site-gw-" + suffix,
                "SITEPUBKEY" + suffix + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "10.8.0.240");
        p.type = "site";
        p.persistAndFlush(); // forces the INSERT → hits the user_id NOT NULL constraint pre-fix

        Peer found = Peer.findById(p.id);
        assertThat(found).isNotNull();
        assertThat(found.userId).isNull();
        assertThat(Peer.<Peer>listAll()).anyMatch(x -> x.id.equals(p.id));
    }
}
