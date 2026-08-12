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
 * GET /api/v1/acl/atlas — the Atlas view's global graph: every User, every
 * Resource, and one edge per contributing grant (role, type-grant, or
 * direct user-grant per ADR-0024). Unlike the earlier per-user version,
 * there is no userId path/query param — the graph always covers everyone.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AtlasResourceTest {

    @PersistenceContext EntityManager em;
    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.deleteAll();
        RoleResourceTypeGrant.deleteAll();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        User.delete("email like ?1", "atlas-test-%");
        // Role.deleteAll() also removes the RoleBootstrap-seeded "Everyone"
        // auto_all role. Reseed it (empty, no grants — same as the real
        // seed) so the invariant "exactly one auto_all role always exists"
        // keeps holding for whatever test runs next; its absence otherwise
        // flakes ConfigImportRoundTripTest depending on suite execution
        // order. Safe here: no test in this class names its own role
        // "Everyone" (they use "atlas-test-everyone"), and an inert
        // grant-less role contributes zero edges to any assertion below.
        roleBootstrap.seedEveryoneRole();
    }

    @Test
    void atlas_userWithZeroResources_stillListedAsUserNode() {
        seedUserOnly();

        given().when().get("/api/v1/acl/atlas")
                .then().statusCode(200)
                .body("users.find { it.name == 'atlas-test-nopeers' }", org.hamcrest.Matchers.notNullValue())
                .body("resources", hasSize(0))
                .body("edges", hasSize(0));
    }

    @Transactional
    void seedUserOnly() {
        User user = User.createNew("atlas-test-nopeers", "atlas-test-nopeers@example.test");
        user.persist();
    }

    @Test
    void atlas_roleGrant_fansOutToEveryUserWithThatRole() {
        String[] userIds = seedTwoUsersOneRole();

        given().when().get("/api/v1/acl/atlas")
                .then().statusCode(200)
                .body("edges.findAll { it.kind == 'role' }", hasSize(2))
                .body("edges[0].roleName", is("Printing"))
                .body("edges.findAll { it.kind == 'role' }.userId",
                        org.hamcrest.Matchers.containsInAnyOrder(userIds[0], userIds[1]));
    }

    @Transactional
    String[] seedTwoUsersOneRole() {
        User userA = User.createNew("atlas-test-a", "atlas-test-a@example.test");
        userA.persist();
        User userB = User.createNew("atlas-test-b", "atlas-test-b@example.test");
        userB.persist();
        Role role = Role.createNew("Printing", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, userA.id).setParameter(2, role.id).executeUpdate();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, userB.id).setParameter(2, role.id).executeUpdate();
        Site site = Site.createNew("atlas-test-site", "10.63.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "LaserJet", "10.63.0.5", null, "printer");
        res.persist();
        RoleResourceGrant.createNew(role.id, res.id, true).persist();
        return new String[] { userA.id, userB.id };
    }

    @Test
    void atlas_autoAllRole_fansOutWithoutExplicitMembership() {
        // Note: other real users (e.g. the bootstrap admin, not scoped by
        // wipeAll's "atlas-test-%" email filter) are also implicit members
        // of an autoAll role, so more than one edge to this resource may
        // exist — this asserts the seeded user's edge is among them,
        // proving the autoAll union fired for a user with zero explicit
        // user_roles rows, rather than asserting an exact edge count.
        String[] ids = seedAutoAllRoleGrant();
        String userId = ids[0], resourceId = ids[1];

        given().when().get("/api/v1/acl/atlas")
                .then().statusCode(200)
                .body("edges.findAll { it.resourceId == '" + resourceId + "' }.userId",
                        org.hamcrest.Matchers.hasItem(userId))
                .body("edges.find { it.userId == '" + userId + "' }.kind", is("role"))
                .body("edges.find { it.userId == '" + userId + "' }.resourceId", is(resourceId));
    }

    @Transactional
    String[] seedAutoAllRoleGrant() {
        // No user_roles row at all — membership comes only from autoAll.
        User user = User.createNew("atlas-test-autoall", "atlas-test-autoall@example.test");
        user.persist();
        Role role = Role.createNew("atlas-test-everyone", null);
        role.autoAll = true;
        role.persist();
        Site site = Site.createNew("atlas-test-autoall-site", "10.66.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "Scanner", "10.66.0.5", null, "printer");
        res.persist();
        RoleResourceGrant.createNew(role.id, res.id, true).persist();
        return new String[] { user.id, res.id };
    }

    @Test
    void atlas_directUserGrant_taggedUserDirect_noRole() {
        String userId = seedDirectGrant();

        given().when().get("/api/v1/acl/atlas")
                .then().statusCode(200)
                .body("edges", hasSize(1))
                .body("edges[0].kind", is("user-direct"))
                .body("edges[0].userId", is(userId))
                .body("edges[0].roleId", org.hamcrest.Matchers.nullValue());
    }

    @Transactional
    String seedDirectGrant() {
        User user = User.createNew("atlas-test-direct", "atlas-test-direct@example.test");
        user.persist();
        Site site = Site.createNew("atlas-test-direct-site", "10.64.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "NAS", "10.64.0.5", null, "nas");
        res.persist();
        UserResourceGrant.createNew(user.id, res.id, true).persist();
        return user.id;
    }

    @Test
    void atlas_typeGrant_taggedTypeGrant() {
        seedTypeGrant();

        given().when().get("/api/v1/acl/atlas")
                .then().statusCode(200)
                .body("edges", hasSize(1))
                .body("edges[0].kind", is("type-grant"))
                .body("edges[0].allPorts", is(true));
    }

    @Transactional
    void seedTypeGrant() {
        User user = User.createNew("atlas-test-typegrant", "atlas-test-typegrant@example.test");
        user.persist();
        Role role = Role.createNew("atlas-test-typerole", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site site = Site.createNew("atlas-test-typesite", "10.65.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "LaserJet2", "10.65.0.5", null, "printer");
        res.persist();
        RoleResourceTypeGrant.createNew(role.id, site.id, "printer").persist();
    }
}
