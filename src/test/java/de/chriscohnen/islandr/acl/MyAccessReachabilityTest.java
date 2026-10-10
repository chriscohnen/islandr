package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
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

import java.io.IOException;
import java.net.ServerSocket;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * GET /acl/my-resources/{id}/reachable (myaccess-reachability-indicator):
 * an on-demand TCP probe of a granted resource, run by the hub because the
 * browser itself cannot connect() to an arbitrary LAN IP (CORS/mixed
 * content/no raw socket).
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class MyAccessReachabilityTest {

    @PersistenceContext EntityManager em;
    @Inject RoleBootstrap roleBootstrap;

    private String userId;
    private String siteId;
    private String reachableResourceId;
    private String unreachableResourceId;
    private String portlessResourceId;
    private ServerSocket listening;

    @BeforeEach
    void setUp() throws IOException {
        wipeAll();
        listening = new ServerSocket(0);
        int closedPort = freePort();
        seed(listening.getLocalPort(), closedPort);
    }

    @AfterEach
    void teardown() throws IOException {
        listening.close();
        wipeAll();
    }

    @Transactional
    void seed(int openPort, int closedPort) {
        User user = User.createNew("Fiona", "fiona@example.test");
        user.persist();
        Role role = Role.createNew("ReachabilityRole", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site site = Site.createNew("ReachNet", "127.0.0.0/8", null);
        site.persist();

        Resource reachable = Resource.createNew(site.id, "Reachable", "127.0.0.1", null, "server");
        reachable.persist();
        ResourcePort.createNew(reachable.id, openPort, null, "tcp", "CUSTOM", null, null, false, false, "native").persist();
        RoleResourceGrant.createNew(role.id, reachable.id, true).persist();

        Resource unreachable = Resource.createNew(site.id, "Unreachable", "127.0.0.2", null, "server");
        unreachable.persist();
        ResourcePort.createNew(unreachable.id, closedPort, null, "tcp", "CUSTOM", null, null, false, false, "native").persist();
        RoleResourceGrant.createNew(role.id, unreachable.id, true).persist();

        Resource portless = Resource.createNew(site.id, "Portless", "127.0.0.3", null, "server");
        portless.persist();
        RoleResourceGrant.createNew(role.id, portless.id, true).persist();

        userId = user.id;
        siteId = site.id;
        reachableResourceId = reachable.id;
        unreachableResourceId = unreachable.id;
        portlessResourceId = portless.id;
    }

    @Transactional
    void wipeAll() {
        RoleResourceTypeGrant.deleteAll();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        roleBootstrap.seedEveryoneRole();
        User.delete("email", "fiona@example.test");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void reportsReachableWithLatencyForAnOpenPort() {
        given().queryParam("userId", userId)
                .when().get("/api/v1/acl/my-resources/" + reachableResourceId + "/reachable")
                .then().statusCode(200)
                .body("reachable", is(true))
                .body("latencyMs", greaterThanOrEqualTo(0));
    }

    @Test
    void reportsUnreachableWithNoLatencyForAClosedPort() {
        given().queryParam("userId", userId)
                .when().get("/api/v1/acl/my-resources/" + unreachableResourceId + "/reachable")
                .then().statusCode(200)
                .body("reachable", is(false))
                .body("latencyMs", nullValue());
    }

    @Test
    void reportsUnknownForAResourceWithNoProbeablePort() {
        given().queryParam("userId", userId)
                .when().get("/api/v1/acl/my-resources/" + portlessResourceId + "/reachable")
                .then().statusCode(200)
                .body("reachable", nullValue())
                .body("latencyMs", nullValue());
    }

    @Test
    void aResourceTheUserHasNoGrantForIs404NotDistinguishableFromMissing() {
        String strangerId = createUngrantedResource();
        given().queryParam("userId", userId)
                .when().get("/api/v1/acl/my-resources/" + strangerId + "/reachable")
                .then().statusCode(404);
    }

    @Transactional
    String createUngrantedResource() {
        Resource r = Resource.createNew(siteId, "Stranger", "127.0.0.4", null, "server");
        r.persist();
        return r.id;
    }
}
