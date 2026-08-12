package de.chriscohnen.islandr.dashboard;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box test of the dashboard roll-up. Seeds a known fixture and asserts
 * every counter + the strip-fill behaviour. The auth-matrix (401/403) is
 * covered by AuthorizationMatrixTest.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DashboardResourceTest {

    @Inject AuditService audit;
    @Inject de.chriscohnen.islandr.acl.RoleBootstrap roleBootstrap;

    @BeforeEach
    @Transactional
    void seed() {
        // Wipe state we control (other tests share the DB).
        AuditLog.deleteAll();
        Site.getEntityManager().createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        Site.getEntityManager().createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        // roles.total below must equal exactly the 2 roles this fixture creates,
        // so the RoleBootstrap "Everyone" role is deliberately NOT reseeded here
        // — only in @AfterEach, once this class is done asserting on the count.

        // Two users — one admin, one plain — so userStats.admins ends up non-zero.
        User admin = User.createNew("Alice Admin", "alice-" + UUID.randomUUID() + "@firma.de");
        admin.isAdmin = true;
        admin.persist();
        User plain = User.createNew("Bob Plain", "bob-" + UUID.randomUUID() + "@firma.de");
        plain.persist();

        // One site with two resources and three ports.
        Site site = Site.createNew("DC1", "10.99.0.0/16", null);
        site.persist();
        Resource r1 = Resource.createNew(site.id, "TerminalDC-1", "10.99.0.5", null, "computer");
        r1.persist();
        Resource r2 = Resource.createNew(site.id, "NAS-1", "10.99.0.10", null, "nas");
        r2.persist();
        ResourcePort.createNew(r1.id, 3389, null, "tcp", "RDP", null, null, true, false, "native").persist();
        ResourcePort.createNew(r1.id, 22, null, "tcp", "SSH", null, null, true, false, "native").persist();
        ResourcePort.createNew(r2.id, 445, null, "tcp", "SMB", null, null, true, false, "native").persist();

        // Two roles, one of which has a grant.
        Role roleWithGrants = Role.createNew("IT", null);
        roleWithGrants.persist();
        Role roleEmpty = Role.createNew("Sales", null);
        roleEmpty.persist();
        RoleResourceGrant g = RoleResourceGrant.createNew(roleWithGrants.id, r1.id, true);
        g.persist();

        // Drop a couple of audit events so the strip has something to show.
        audit.logEvent("admin", "peer.create", "Peer:test-1", Map.of());
        audit.logEvent("admin", "user.admin_grant", "User:" + admin.id, Map.of());

        // Three peers for the topology widget: one live, one stale, one disabled.
        // We don't go through PeerService.create here because that would also
        // touch the mock WireGuard adapter — the dashboard only reads rows.
        Peer.deleteAll();
        Peer live = Peer.createNew(plain.id, "macbook-felix",
                "k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1=", "10.99.0.20");
        // Inside the 5-min live window — pick something clearly within it
        // so the test isn't tripped by clock jitter at the boundary.
        live.lastSeenAt = java.time.Instant.now().minus(java.time.Duration.ofSeconds(30));
        live.persist();
        Peer stale = Peer.createNew(plain.id, "site-hamburg",
                "k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2k2=", "10.99.0.21");
        stale.type = "site";
        stale.persist();
        Peer disabled = Peer.createNew(plain.id, "nas-keller",
                "k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3k3=", "10.99.0.22");
        disabled.enabled = false;
        disabled.persist();
    }

    // Role.deleteAll() in seed() above removes the RoleBootstrap-seeded
    // "Everyone" auto_all role. Reseed after this class's tests are done so
    // whichever test class runs next still finds the invariant "exactly one
    // auto_all role always exists" holding — its absence otherwise flakes
    // ConfigImportRoundTripTest depending on suite execution order.
    @AfterEach
    @Transactional
    void reseedEveryoneRole() {
        roleBootstrap.seedEveryoneRole();
    }

    @Test
    void dashboard_returnsAggregatedCounters() {
        JsonPath body = given().when().get("/api/v1/dashboard")
                .then().statusCode(200).extract().jsonPath();

        // Users: the suite-shared DB may carry a few extra users from earlier
        // tests, but our seeded admin must be visible. Assert the floor.
        assertThat(body.getLong("users.admins")).isGreaterThanOrEqualTo(1L);
        assertThat(body.getLong("users.total")).isGreaterThanOrEqualTo(2L);

        // Roles + grants are scoped to what we just wiped + seeded.
        assertThat(body.getLong("roles.total")).isEqualTo(2L);
        assertThat(body.getLong("roles.withGrants")).isEqualTo(1L);

        // Resources too — wipe controls these.
        assertThat(body.getLong("resources.sites")).isEqualTo(1L);
        assertThat(body.getLong("resources.resources")).isEqualTo(2L);
        assertThat(body.getLong("resources.ports")).isEqualTo(3L);

        // Recent audit strip non-empty — at minimum the two we just wrote.
        assertThat(body.getList("recentAudit").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void dashboard_setupBlock_reflectsCurrentState() {
        JsonPath body = given().when().get("/api/v1/dashboard")
                .then().statusCode(200).extract().jsonPath();
        // V3 seeds the wgServerPublicKey with 'PLACEHOLDER...' — fresh in-memory
        // DB still has it, so wgConfigured must be false out of the box.
        assertThat(body.getBoolean("setup.wgConfigured")).isFalse();
        // No OIDC provider activated in the test fixture.
        assertThat(body.getString("setup.oidcProvider")).isNull();
        // Default retention is 'never'.
        assertThat(body.getString("setup.privateKeyRetention")).isEqualTo("never");
    }

    @Test
    void dashboard_topology_listsSitesAndResources() {
        JsonPath body = given().when().get("/api/v1/dashboard")
                .then().statusCode(200).extract().jsonPath();

        // The setup seeds one site ("DC1") and two resources within it.
        assertThat(body.getList("topology.sites").size()).isEqualTo(1);
        assertThat(body.getString("topology.sites[0].name")).isEqualTo("DC1");
        assertThat(body.getInt("topology.sites[0].resourceCount")).isEqualTo(2);

        assertThat(body.getList("topology.resources").size()).isEqualTo(2);
        List<String> resourceNames = body.getList("topology.resources.name");
        assertThat(resourceNames).containsExactlyInAnyOrder("TerminalDC-1", "NAS-1");

        // Port counts: TerminalDC-1 has 2 (RDP+SSH), NAS-1 has 1 (SMB).
        int totalPorts = body.getList("topology.resources.portCount", Integer.class)
                .stream().mapToInt(Integer::intValue).sum();
        assertThat(totalPorts).isEqualTo(3);

        assertThat(body.getInt("topology.resourceOverflow")).isEqualTo(0);
    }

    /** Backs the world-map topology view (ADR-0021, #11). */
    @Test
    void dashboard_topology_exposesGatewayAndHubCoordinates() {
        seedGatewayAndHubCoordinates();

        JsonPath body = given().when().get("/api/v1/dashboard")
                .then().statusCode(200).extract().jsonPath();

        assertThat(body.getDouble("topology.sites[0].gatewayLat")).isEqualTo(50.1109);
        assertThat(body.getDouble("topology.sites[0].gatewayLng")).isEqualTo(8.6821);
        assertThat(body.getDouble("topology.hubLat")).isEqualTo(52.52);
        assertThat(body.getDouble("topology.hubLon")).isEqualTo(13.405);
    }

    @Transactional
    void seedGatewayAndHubCoordinates() {
        User owner = User.createNew("Site Owner", "owner-" + UUID.randomUUID() + "@firma.de");
        owner.persist();
        Peer gateway = Peer.createNew(owner.id, "gw-frankfurt",
                "k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4k4=", "10.99.0.30");
        gateway.type = "site";
        gateway.lat = 50.1109;
        gateway.lng = 8.6821;
        gateway.persist();

        Site site = Site.find("name", "DC1").firstResult();
        site.gatewayPeerId = gateway.id;

        de.chriscohnen.islandr.settings.Settings s =
                de.chriscohnen.islandr.settings.Settings.findById(de.chriscohnen.islandr.settings.Settings.SINGLETON_ID);
        s.hubLat = 52.52;
        s.hubLon = 13.405;
    }

    @Test
    void dashboard_topology_livePeers_onlyRecentHandshakes() {
        JsonPath body = given().when().get("/api/v1/dashboard")
                .then().statusCode(200).extract().jsonPath();

        // Of the three seeded peers, only macbook-felix has a lastSeenAt
        // within the live window (5 min). The other two are stale/disabled
        // and must NOT appear in the live list.
        List<String> liveNames = body.getList("topology.livePeers.name");
        assertThat(liveNames).containsExactly("macbook-felix");
    }

    @Test
    void dashboard_stripsCapAt8() {
        // Pump 12 audit events; strip should clip to STRIP_SIZE=8.
        for (int i = 0; i < 12; i++) {
            audit.logEvent("admin", "settings.update", "Settings:singleton",
                    Map.of("iter", i));
        }
        JsonPath body = given().when().get("/api/v1/dashboard")
                .then().statusCode(200).extract().jsonPath();
        assertThat(body.getList("recentAudit").size()).isLessThanOrEqualTo(8);
    }
}
