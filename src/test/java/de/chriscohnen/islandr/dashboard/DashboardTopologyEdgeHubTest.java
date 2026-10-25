package de.chriscohnen.islandr.dashboard;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * edge-hub-radial-topology (loop/TASK.md): {@code topology.livePeers[].edgeHubId}
 * is the data the radial diagram needs to draw a peer under its edge hub
 * instead of the central hub — same derivation as edge-peer-home
 * (AclResolutionService#reachableEdgeHubIdsForPeer), just surfaced on the dashboard
 * roll-up this diagram already consumes.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DashboardTopologyEdgeHubTest {

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    @BeforeEach
    @Transactional
    void wipe() { wipeAll(); }

    @AfterEach
    @Transactional
    void cleanup() { wipeAll(); }

    void wipeAll() {
        Peer.delete("name like ?1", "edgetopo-%");
        UserResourceGrant.deleteAll();
        Resource.delete("name like ?1", "edgetopo-%");
        Site.delete("name like ?1", "Edgetopo-%");
        EdgeHub.delete("name like ?1", "Edgetopo-%");
        User.delete("email like ?1", "edgetopo-%@example.test");
    }

    private String edgeId, reachOnlyPeerId, assignedPeerId, unrelatedPeerId;

    @Transactional
    void seedScenario() {
        EdgeHub edge = EdgeHub.createNew("Edgetopo-" + uniq(), 1.0, 1.0, null);
        edge.persist();
        edgeId = edge.id;
        Site site = Site.createNew("Edgetopo-Site-" + uniq(), "10.98.0.0/24", null);
        site.edgeHubId = edge.id;
        site.persist();
        Resource res = Resource.createNew(site.id, "edgetopo-res", "10.98.0.5", null, "computer");
        res.persist();
        User user = User.createNew("Edgetopo User", "edgetopo-" + uniq() + "@example.test");
        user.persist();
        UserResourceGrant.createNew(user.id, res.id, true).persist();

        // May reach a resource behind the edge, but dials the central hub.
        // This is the peer the diagram used to misplace.
        Peer reachOnly = Peer.createNew(user.id, "edgetopo-reach-only-peer",
                "K" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "10.98.0.20");
        reachOnly.lastSeenAt = Instant.now().minus(Duration.ofSeconds(30));
        reachOnly.persist();
        reachOnlyPeerId = reachOnly.id;

        // Actually dials the edge (ADR-I-0003).
        Peer assigned = Peer.createNew(user.id, "edgetopo-assigned-peer",
                "K" + uniq() + "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=", "10.98.0.22");
        assigned.edgeHubId = edge.id;
        assigned.lastSeenAt = Instant.now().minus(Duration.ofSeconds(30));
        assigned.persist();
        assignedPeerId = assigned.id;

        User unrelatedUser = User.createNew("Edgetopo Unrelated", "edgetopo-unrelated-" + uniq() + "@example.test");
        unrelatedUser.persist();
        Peer unrelated = Peer.createNew(unrelatedUser.id, "edgetopo-unrelated-peer",
                "K" + uniq() + "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", "10.98.0.21");
        unrelated.lastSeenAt = Instant.now().minus(Duration.ofSeconds(30));
        unrelated.persist();
        unrelatedPeerId = unrelated.id;
    }

    /**
     * A dot in the diagram says where a device <i>is connected</i>, which is
     * not the same as where it is allowed to reach something.
     *
     * <p>Until 2026-10-20 this very test asserted the opposite: the live-peer
     * entry carried the edge derived from the owner's grants, so a device with
     * one grant to a resource behind an edge was drawn orbiting that edge
     * while it was in fact dialling the central hub. The assertion was
     * faithful to the code and wrong about the world; ADR-I-0003 replaced the
     * derivation with {@code peers.edge_hub_id}, and the case that used to
     * pass is now the case that must not.
     */
    @Test
    void aLivePeerIsPlacedAtTheHubItDialsNotAtOneItCanReach() {
        seedScenario();

        JsonPath body = given().when().get("/api/v1/dashboard").then().statusCode(200).extract().jsonPath();
        List<String> ids = body.getList("topology.livePeers.id");
        int reachOnly = ids.indexOf(reachOnlyPeerId);
        int assigned = ids.indexOf(assignedPeerId);
        int unrelated = ids.indexOf(unrelatedPeerId);
        assertThat(reachOnly).isGreaterThanOrEqualTo(0);
        assertThat(assigned).isGreaterThanOrEqualTo(0);
        assertThat(unrelated).isGreaterThanOrEqualTo(0);

        // Grants to something behind the edge do not move the dot.
        assertThat(body.getString("topology.livePeers[" + reachOnly + "].edgeHubId")).isNull();
        // The assignment does, and it is the only thing that does.
        assertThat(body.getString("topology.livePeers[" + assigned + "].edgeHubId")).isEqualTo(edgeId);
        assertThat(body.getString("topology.livePeers[" + unrelated + "].edgeHubId")).isNull();

        List<String> edgeHubIds = body.getList("topology.edgeHubs.id");
        assertThat(edgeHubIds).contains(edgeId);
    }
}
