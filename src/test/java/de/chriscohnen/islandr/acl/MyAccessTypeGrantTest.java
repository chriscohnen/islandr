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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * A type-grant ("all printers in Homeoffice") must surface a matching
 * resource on GET /acl/my-resources, the same as a concrete RoleResourceGrant
 * would — this is the self-service-portal-facing half of the ACL type-grants
 * feature (2026-07-28); RuleBuilder's enforcement half is covered in
 * FirewallTest, the CRUD surface in AclTypeGrantResourceTest.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class MyAccessTypeGrantTest {

    @PersistenceContext EntityManager em;
    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

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
        // Reseed the RoleBootstrap "Everyone" auto_all role that Role.deleteAll()
        // just removed (empty, no grants — same as the real seed) so the
        // invariant "exactly one auto_all role always exists" keeps holding
        // for whatever test runs next; its absence otherwise flakes
        // ConfigImportRoundTripTest depending on suite execution order.
        roleBootstrap.seedEveryoneRole();
        // Not User.deleteAll() — that would also wipe the ENV-bootstrap-seeded
        // admin@local row other test classes (e.g. AdminUserBootstrapTest) rely
        // on existing exactly once in this shared test DB. Scope to our own user.
        User.delete("email", "dana@example.test");
    }

    @Test
    void myResources_includesResourceCoveredOnlyByATypeGrant() {
        // Each step commits its own transaction before the REST call below —
        // a single @Transactional wrapping both the setup AND the HTTP call
        // deadlocks SQLite's shared cache (the REST call opens its own
        // connection while this method's transaction still holds locks).
        String userId = seed();

        given().queryParam("userId", userId)
                .when().get("/api/v1/acl/my-resources")
                .then().statusCode(200)
                .body("resources", hasSize(1))
                .body("resources[0].name", is("LaserJet"))
                .body("resources[0].siteId", is(homeId));
    }

    private String homeId;

    @Transactional
    String seed() {
        User user = User.createNew("Dana", "dana@example.test");
        user.persist();
        Role role = Role.createNew("Printing", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site home = Site.createNew("Homeoffice", "10.31.0.0/16", null);
        home.persist();
        Site office = Site.createNew("Office", "10.32.0.0/16", null);
        office.persist();
        Resource homePrinter = Resource.createNew(home.id, "LaserJet", "10.31.0.5", null, "printer");
        homePrinter.persist();
        Resource officePrinter = Resource.createNew(office.id, "OfficeJet", "10.32.0.5", null, "printer");
        officePrinter.persist();
        ResourcePort.createNew(homePrinter.id, 631, null, "tcp", "IPP", null, null, true, false, "native").persist();
        ResourcePort.createNew(officePrinter.id, 631, null, "tcp", "IPP", null, null, true, false, "native").persist();
        // No concrete RoleResourceGrant anywhere — access comes only from the type-grant.
        RoleResourceTypeGrant.createNew(role.id, home.id, "printer").persist();
        homeId = home.id;
        return user.id;
    }
}
