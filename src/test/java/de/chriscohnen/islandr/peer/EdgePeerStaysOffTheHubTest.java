package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleNetworkGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.firewall.RuleBuilder;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.MockWgAdapter;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * edge-drift-exclusion (ADR-I-0002, ADR-I-0003): a peer that dials an edge hub
 * has no business on the central hub — not on its WireGuard interface, and not
 * in its ruleset.
 *
 * <p><b>Why the interface half matters.</b> {@code reconcileDrift} exists to
 * repair kernel state the hub lost, and it defines "lost" as "enabled in the
 * database but missing from the interface". An edge peer matches that
 * description permanently, so every tick pushed it onto the central hub with
 * the same public key and the same {@code AllowedIPs} it has at its edge. That
 * is the double-assignment class of bug from 0.21.0, this time produced by the
 * self-healing: {@code AllowedIPs} is also the inbound filter, so it looks
 * correct until traffic arrives at the wrong hub.
 *
 * <p><b>Why the ruleset half matters.</b> Traffic that never leaves the edge
 * does not pass the hub, so a rule about it is unenforceable — and an
 * unenforceable rule that reads like enforcement is worse than no rule.
 *
 * <p>The condition is the field {@code peers.edge_hub_id}, not the derivation
 * {@code reachableEdgeHubIdsForPeer}. That distinction is why this task was blocked for
 * a month: the derivation says where a peer may <i>reach</i>, and filtering on
 * it would have taken working central-hub access away from peers that merely
 * have a grant somewhere behind an edge.
 */
@QuarkusTest
class EdgePeerStaysOffTheHubTest {

    private static final String MARK = "edgedrift";

    @Inject PeerService peers;
    @Inject RuleBuilder builder;
    @Inject WgAdapter wg;
    @PersistenceContext EntityManager em;

    private MockWgAdapter mock() {
        return (MockWgAdapter) io.quarkus.arc.ClientProxy.unwrap(wg);
    }

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        em.createNativeQuery("DELETE FROM user_roles WHERE role_id IN "
                + "(SELECT id FROM roles WHERE name LIKE '" + MARK + "-%')").executeUpdate();
        RoleNetworkGrant.delete("roleId in (select r.id from Role r where r.name like ?1)", MARK + "-%");
        Peer.delete("name like ?1", MARK + "-%");
        EdgeHub.delete("name like ?1", MARK + "-%");
        Site.delete("name like ?1", MARK + "-%");
        Role.delete("name like ?1", MARK + "-%");
        User.delete("email like ?1", MARK + "-%");
    }

    /** Own block — the low addresses are hardcoded by other classes. */
    private final java.util.concurrent.atomic.AtomicInteger host =
            new java.util.concurrent.atomic.AtomicInteger(201);

    private String nextIp() {
        return "10.8.0." + host.getAndIncrement();
    }

    /** A user with a grant on a whole site network, so every peer of theirs
     *  would otherwise show up in the ruleset. */
    @Transactional
    String grantedUser() {
        User u = User.createNew("Edge Drift", MARK + "-" + UUID.randomUUID() + "@example.test");
        u.persist();
        Role role = Role.createNew(MARK + "-" + UUID.randomUUID(), null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, u.id).setParameter(2, role.id).executeUpdate();
        Site site = Site.createNew(MARK + "-" + UUID.randomUUID(), "10.66.0.0/16", null);
        site.persist();
        RoleNetworkGrant.createNew(role.id, site.id).persist();
        return u.id;
    }

    @Transactional
    String createEdge() {
        EdgeHub e = EdgeHub.createNew(MARK + "-" + UUID.randomUUID(), -33.45, -70.67, null);
        e.endpoint = "edge.example:51820";
        e.serverPublicKey = "sWPGBA3mT1zuSSVN2Eut1v3dMFqoKFsKnMR8wBTQGEo=";
        e.persist();
        return e.id;
    }

    @Transactional
    Peer createPeer(String userId, String edgeHubId, String ip) {
        Peer p = Peer.createNew(userId, MARK + "-" + UUID.randomUUID(), "pk-" + UUID.randomUUID(), ip);
        p.edgeHubId = edgeHubId;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    @Test
    void driftRepushLeavesAPeerThatDialsAnEdgeAlone() {
        String userId = grantedUser();
        String edgeId = createEdge();
        String edgeKey = createPeer(userId, edgeId, nextIp()).publicKey;
        String centralKey = createPeer(userId, null, nextIp()).publicKey;

        // Nothing is on the interface, so every enabled peer looks "missing"
        // — which is exactly the state the poller tries to repair.
        peers.repushMissingPeers(Set.of());

        var live = mock().showPeers("wg0").stream().map(WgAdapter.PeerStatus::publicKey).toList();
        assertThat(live)
                .as("a peer at the central hub is still repaired")
                .contains(centralKey);
        assertThat(live)
                .as("a peer that dials an edge must never be pushed onto the hub")
                .doesNotContain(edgeKey);
    }

    @Test
    void anExplicitRepushOfAllPeersLeavesThemAloneToo() {
        String userId = grantedUser();
        String edgeKey = createPeer(userId, createEdge(), nextIp()).publicKey;

        peers.repushEnabledPeers();

        assertThat(mock().showPeers("wg0").stream().map(WgAdapter.PeerStatus::publicKey).toList())
                .as("the manual repush is the same write, by another trigger")
                .doesNotContain(edgeKey);
    }

    @Test
    @Transactional
    void theHubsRulesetDoesNotMentionAPeerAtAnEdge() {
        String userId = grantedUser();
        String edgeIp = nextIp();
        String centralIp = nextIp();
        createPeer(userId, createEdge(), edgeIp);
        createPeer(userId, null, centralIp);

        String text = builder.build().rulesetText();

        assertThat(text)
                .as("the central peer's grant is enforceable and must be rendered")
                .contains("ip saddr " + centralIp);
        assertThat(text)
                .as("traffic that never passes the hub cannot be filtered there")
                .doesNotContain(edgeIp);
    }
}
