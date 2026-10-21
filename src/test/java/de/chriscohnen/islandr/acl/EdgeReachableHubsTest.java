package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

/**
 * edge-peer-home (loop/TASK.md, ADR-I-0002, Entscheidung 2026-10-02 Variante
 * D): which {@link EdgeHub}(s) a peer belongs to, derived from its owner's
 * effective grants rather than an assigned field. Covers
 * {@link AclResolutionService#reachableEdgeHubIdsForUser}/{@code reachableEdgeHubIdsForPeer}
 * directly (fast, one grant type per test) and the
 * {@code GET /api/v1/peers/{id}/reachable-edges} endpoint that exposes it.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EdgeReachableHubsTest {

    @PersistenceContext EntityManager em;
    @Inject AclResolutionService acl;
    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        Peer.deleteAll();
        RoleNetworkGrant.deleteAll();
        RoleResourceTypeGrant.deleteAll();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        roleBootstrap.seedEveryoneRole();
        EdgeHub.deleteAll();
        User.delete("email like ?1", "edgehome-%@example.test");
    }

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    @Transactional
    EdgeHub createEdgeHub(String name) {
        EdgeHub e = EdgeHub.createNew(name, 1.0, 1.0, null);
        e.persist();
        return e;
    }

    @Transactional
    Site createSite(String name, String cidr, String edgeHubId) {
        Site s = Site.createNew(name, cidr, null);
        s.edgeHubId = edgeHubId;
        s.persist();
        return s;
    }

    @Transactional
    User createUser() {
        User u = User.createNew("Edge Home " + uniq(), "edgehome-" + uniq() + "@example.test");
        u.persist();
        return u;
    }

    @Transactional
    Role createRoleFor(String userId) {
        Role role = Role.createNew("EdgeHomeRole-" + uniq(), null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, userId).setParameter(2, role.id).executeUpdate();
        return role;
    }

    @Transactional
    Resource createResource(String siteId) {
        Resource r = Resource.createNew(siteId, "res-" + uniq(), "10.99.0.5", null, "computer");
        r.persist();
        return r;
    }

    // Asks the server for a free IP rather than hardcoding one — assignedIp
    // is unique across the whole test suite's shared in-memory DB (same
    // reasoning as UserDisableCascadeTest#nextIp).
    String nextIp() {
        return given().when().get("/api/v1/peers/next-ip")
                .then().statusCode(200).extract().path("assignedIp");
    }

    String createClientPeer(String userId) {
        String ip = nextIp();
        return persistClientPeer(userId, ip);
    }

    @Transactional
    String persistClientPeer(String userId, String ip) {
        Peer p = Peer.createNew(userId, "peer-" + uniq(),
                "PUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", ip);
        p.persist();
        return p.id;
    }

    @Test
    void userWithNoGrants_hasNoEdgeHome() {
        User user = createUser();
        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).isEmpty();
    }

    @Test
    @Transactional
    void directResourceGrant_inAnEdgeHubSite_makesTheEdgeReachable() {
        EdgeHub edge = createEdgeHub("Munich-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.70.0.0/24", edge.id);
        Resource res = createResource(site.id);
        User user = createUser();
        UserResourceGrant.createNew(user.id, res.id, true).persist();

        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).containsExactly(edge.id);
    }

    @Test
    @Transactional
    void roleResourceGrant_inAnEdgeHubSite_makesTheEdgeReachable() {
        EdgeHub edge = createEdgeHub("Cologne-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.71.0.0/24", edge.id);
        Resource res = createResource(site.id);
        User user = createUser();
        Role role = createRoleFor(user.id);
        RoleResourceGrant.createNew(role.id, res.id, true).persist();

        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).containsExactly(edge.id);
    }

    @Test
    @Transactional
    void roleNetworkGrant_inAnEdgeHubSite_makesTheEdgeReachable() {
        EdgeHub edge = createEdgeHub("Hamburg-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.72.0.0/24", edge.id);
        User user = createUser();
        Role role = createRoleFor(user.id);
        RoleNetworkGrant.createNew(role.id, site.id).persist();

        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).containsExactly(edge.id);
    }

    @Test
    @Transactional
    void roleTypeGrant_inAnEdgeHubSite_makesTheEdgeReachable() {
        EdgeHub edge = createEdgeHub("Berlin-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.73.0.0/24", edge.id);
        createResource(site.id); // type grant needs a matching resource to exist, doesn't need a direct grant on it
        User user = createUser();
        Role role = createRoleFor(user.id);
        RoleResourceTypeGrant.createNew(role.id, site.id, "computer").persist();

        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).containsExactly(edge.id);
    }

    @Test
    @Transactional
    void grantsAcrossTwoEdgeHubSites_bothAreReachable() {
        EdgeHub munich = createEdgeHub("Munich2-" + uniq());
        EdgeHub cologne = createEdgeHub("Cologne2-" + uniq());
        Site munichSite = createSite("MunichSite-" + uniq(), "10.74.0.0/24", munich.id);
        Site cologneSite = createSite("CologneSite-" + uniq(), "10.75.0.0/24", cologne.id);
        User user = createUser();
        Role role = createRoleFor(user.id);
        RoleNetworkGrant.createNew(role.id, munichSite.id).persist();
        RoleNetworkGrant.createNew(role.id, cologneSite.id).persist();

        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).containsExactlyInAnyOrder(munich.id, cologne.id);
    }

    @Test
    @Transactional
    void grantInACentralHubSite_isNotAnEdgeHome() {
        // No edgeHubId set — served by the central hub, which is implicit
        // and never appears in the derived set (see AclResolutionService
        // javadoc: "a peer with nothing to reach doesn't belong anywhere
        // but the central hub either" — the reverse holds too: a central-
        // only site contributes no edge membership).
        Site site = createSite("CentralSite-" + uniq(), "10.76.0.0/24", null);
        Resource res = createResource(site.id);
        User user = createUser();
        UserResourceGrant.createNew(user.id, res.id, true).persist();

        assertThat(acl.reachableEdgeHubIdsForUser(user.id)).isEmpty();
    }

    @Test
    void siteGatewayPeer_derivesFromItsSiteDirectly_noGrantNeeded() {
        String ip = nextIp();
        EdgeHub edge = createEdgeHub("Gateway-" + uniq());
        Peer gw = persistSiteGatewayPeer(ip, edge.id);

        assertThat(acl.reachableEdgeHubIdsForPeer(gw)).containsExactly(edge.id);
    }

    @Transactional
    Peer persistSiteGatewayPeer(String ip, String edgeHubId) {
        Peer gw = Peer.createNew(null, "gw-" + uniq(), "GWPUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAA=", ip);
        gw.type = "site";
        gw.persist();
        createSite("GwSite-" + uniq(), "10.77.0.0/24", edgeHubId).gatewayPeerId = gw.id;
        return gw;
    }

    @Test
    void reachableEdgesEndpoint_returnsIdsAndNames() {
        EdgeHub edge = createEdgeHub("Rest-" + uniq());
        Site site = createSite("RestSite-" + uniq(), "10.78.0.0/24", edge.id);
        Resource res = createResource(site.id);
        User user = createUser();
        createGrant(user.id, res.id);
        String peerId = createClientPeer(user.id);

        given().when().get("/api/v1/peers/" + peerId + "/reachable-edges")
                .then().statusCode(200)
                .body("edgeHubIds", contains(edge.id))
                .body("edgeHubNames", contains(edge.name));
    }

    @Test
    void reachableEdgesEndpoint_emptyForAPeerWithNoGrants() {
        User user = createUser();
        String peerId = createClientPeer(user.id);

        given().when().get("/api/v1/peers/" + peerId + "/reachable-edges")
                .then().statusCode(200)
                .body("edgeHubIds", empty())
                .body("edgeHubNames", empty());
    }

    @Transactional
    void createGrant(String userId, String resourceId) {
        UserResourceGrant.createNew(userId, resourceId, true).persist();
    }
}
