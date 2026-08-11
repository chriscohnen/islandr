package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
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
 * GET /api/v1/acl/atlas/{userId} — the Atlas view's graph endpoint. Reuses
 * AclResolutionService.resolveRoleIds (shared with MyAccessResource, see
 * MyAccessTypeGrantTest for that side), but keeps grants per-role rather
 * than merging them, since Atlas draws one line per contributing role.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AtlasResourceTest {

    @PersistenceContext EntityManager em;

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
        Peer.delete("name like ?1", "atlas-test-%");
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        // Not User.deleteAll() — shared test DB, other test classes rely on the
        // ENV-bootstrap admin row surviving. Scope to our own seeded user only.
        User.delete("email", "atlas-user@example.test");
    }

    @Test
    void atlas_userWithNoPeers_returnsEmptyGraph() {
        String userId = seedUserOnly();

        given().when().get("/api/v1/acl/atlas/" + userId)
                .then().statusCode(200)
                .body("peers", hasSize(0))
                .body("resources", hasSize(0))
                .body("edges", hasSize(0));
    }

    @Transactional
    String seedUserOnly() {
        User user = User.createNew("Atlas User", "atlas-user@example.test");
        user.persist();
        return user.id;
    }

    @Test
    void atlas_reachableAndUnreachableResourcesInSameSite_bothListed() {
        String userId = seedWithOneGrant();

        given().when().get("/api/v1/acl/atlas/" + userId)
                .then().statusCode(200)
                .body("peers", hasSize(1))
                .body("peers[0].name", is("atlas-test-laptop"))
                .body("resources", hasSize(2))
                .body("resources.find { it.name == 'LaserJet' }.reachable", is(true))
                .body("resources.find { it.name == 'LaserJet' }.ownership", is("grant"))
                .body("resources.find { it.name == 'OfficeJet' }.reachable", is(false))
                .body("edges", hasSize(1))
                .body("edges[0].roleName", is("Printing"))
                .body("edges[0].allPorts", is(true));
    }

    @Transactional
    String seedWithOneGrant() {
        User user = User.createNew("Atlas User", "atlas-user@example.test");
        user.persist();
        Peer.createNew(user.id, "atlas-test-laptop", "pubkey-atlas-1", "10.99.0.2").persist();
        Role role = Role.createNew("Printing", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site home = Site.createNew("Homeoffice", "10.31.0.0/16", null);
        home.persist();
        Resource laser = Resource.createNew(home.id, "LaserJet", "10.31.0.5", null, "printer");
        laser.persist();
        Resource office = Resource.createNew(home.id, "OfficeJet", "10.31.0.6", null, "printer");
        office.persist();
        RoleResourceGrant.createNew(role.id, laser.id, true).persist();
        return user.id;
    }

    @Test
    void atlas_resourceGrantedByTwoRoles_producesTwoEdges() {
        String userId = seedTwoRoleGrant();

        given().when().get("/api/v1/acl/atlas/" + userId)
                .then().statusCode(200)
                .body("edges", hasSize(2));
    }

    @Transactional
    String seedTwoRoleGrant() {
        User user = User.createNew("Atlas User", "atlas-user@example.test");
        user.persist();
        Peer.createNew(user.id, "atlas-test-laptop", "pubkey-atlas-2", "10.99.0.3").persist();
        Role roleA = Role.createNew("Printing", null);
        roleA.persist();
        Role roleB = Role.createNew("Facilities", null);
        roleB.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, roleA.id).executeUpdate();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, roleB.id).executeUpdate();
        Site home = Site.createNew("Homeoffice", "10.31.0.0/16", null);
        home.persist();
        Resource laser = Resource.createNew(home.id, "LaserJet", "10.31.0.5", null, "printer");
        laser.persist();
        RoleResourceGrant.createNew(roleA.id, laser.id, true).persist();
        RoleResourceGrant.createNew(roleB.id, laser.id, true).persist();
        return user.id;
    }

    @Test
    void atlas_typeGrantResource_taggedAndReachable() {
        String userId = seedTypeGrant();

        given().when().get("/api/v1/acl/atlas/" + userId)
                .then().statusCode(200)
                .body("resources.find { it.name == 'LaserJet' }.reachable", is(true))
                .body("resources.find { it.name == 'LaserJet' }.ownership", is("type-grant"))
                .body("edges", hasSize(1))
                .body("edges[0].allPorts", is(true));
    }

    @Transactional
    String seedTypeGrant() {
        User user = User.createNew("Atlas User", "atlas-user@example.test");
        user.persist();
        Peer.createNew(user.id, "atlas-test-laptop", "pubkey-atlas-3", "10.99.0.4").persist();
        Role role = Role.createNew("Printing", null);
        role.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        Site home = Site.createNew("Homeoffice", "10.31.0.0/16", null);
        home.persist();
        Resource laser = Resource.createNew(home.id, "LaserJet", "10.31.0.5", null, "printer");
        laser.persist();
        RoleResourceTypeGrant.createNew(role.id, home.id, "printer").persist();
        return user.id;
    }
}
