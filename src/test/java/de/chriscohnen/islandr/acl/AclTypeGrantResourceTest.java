package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST-level CRUD for {@code /api/v1/acl/type-grants} — "all printers in
 * Homeoffice" role grants scoped by resource type + site, additive-only,
 * always all-ports (ACL type-grants, 2026-07-28). Enforcement expansion
 * (RuleBuilder) has its own test in {@code FirewallTest}; this covers the
 * CRUD surface and its validation only.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AclTypeGrantResourceTest {

    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        // FK order: type-grants/ports/resources before sites (resources.site_id
        // has no ON DELETE CASCADE) — same convention as FirewallTest/AclEndpointsTest.
        RoleResourceTypeGrant.deleteAll();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        AuditLog.deleteAll();
        // Reseed the RoleBootstrap "Everyone" auto_all role that Role.deleteAll()
        // just removed (empty, no grants — same as the real seed) so the
        // invariant "exactly one auto_all role always exists" keeps holding
        // for whatever test runs next; its absence otherwise flakes
        // ConfigImportRoundTripTest depending on suite execution order.
        roleBootstrap.seedEveryoneRole();
    }

    @Transactional
    String latestTarget() {
        AuditLog row = AuditLog.<AuditLog>find(
                "action not like ?1 order by createdAt desc, id", "firewall.%").firstResult();
        return row == null ? null : row.target;
    }

    @Transactional
    Role persistRole(String name) {
        Role r = Role.createNew(name, null);
        r.persist();
        return r;
    }

    @Transactional
    Site persistSite(String name, String cidr) {
        Site s = Site.createNew(name, cidr, null);
        s.persist();
        return s;
    }

    @Test
    void create_thenList_returnsTheGrantWithSiteName() {
        Role role = persistRole("Printing");
        Site site = persistSite("Homeoffice", "10.30.0.0/16");

        given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id, "resourceType", "printer"))
                .when().post("/api/v1/acl/type-grants")
                .then().statusCode(201)
                .body("roleId", equalTo(role.id))
                .body("siteId", equalTo(site.id))
                .body("siteName", equalTo("Homeoffice"))
                .body("resourceType", equalTo("printer"));

        given().when().get("/api/v1/acl/type-grants")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].resourceType", equalTo("printer"));
    }

    @Test
    void create_sameRuleTwice_isIdempotentNotDuplicated() {
        Role role = persistRole("Printing");
        Site site = persistSite("Homeoffice", "10.30.0.0/16");
        Map<String, String> body = Map.of("roleId", role.id, "siteId", site.id, "resourceType", "printer");

        given().contentType("application/json").body(body)
                .when().post("/api/v1/acl/type-grants").then().statusCode(201);
        given().contentType("application/json").body(body)
                .when().post("/api/v1/acl/type-grants").then().statusCode(201);

        given().when().get("/api/v1/acl/type-grants")
                .then().statusCode(200).body("$", hasSize(1));
    }

    @Test
    void create_unknownRole_returns404() {
        Site site = persistSite("Homeoffice", "10.30.0.0/16");
        given().contentType("application/json")
                .body(Map.of("roleId", "not-a-real-role", "siteId", site.id, "resourceType", "printer"))
                .when().post("/api/v1/acl/type-grants")
                .then().statusCode(404);
    }

    @Test
    void create_unknownSite_returns404() {
        Role role = persistRole("Printing");
        given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", "not-a-real-site", "resourceType", "printer"))
                .when().post("/api/v1/acl/type-grants")
                .then().statusCode(404);
    }

    @Test
    void delete_removesTheGrant() {
        Role role = persistRole("Printing");
        Site site = persistSite("Homeoffice", "10.30.0.0/16");
        String id = given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id, "resourceType", "printer"))
                .when().post("/api/v1/acl/type-grants")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/v1/acl/type-grants/" + id).then().statusCode(204);
        given().when().get("/api/v1/acl/type-grants").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void delete_unknownId_returns404() {
        given().when().delete("/api/v1/acl/type-grants/not-a-real-id").then().statusCode(404);
    }

    // Regression: the first cut of this endpoint audited raw role_id/site_id
    // UUIDs as the target ("was actually granted here?" unanswerable from the
    // audit log alone) instead of resolved names, unlike every other ACL
    // mutation (AclMatrixResource's "Grant:<roleName>/<resourceName>"
    // pattern). Reported directly against the running app, 2026-07-28.
    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Test
    void create_auditTarget_showsRoleAndSiteNamesNotRawIds() {
        Role role = persistRole("Printing");
        Site site = persistSite("Homeoffice", "10.30.0.0/16");

        given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id, "resourceType", "printer"))
                .when().post("/api/v1/acl/type-grants").then().statusCode(201);

        String target = latestTarget();
        assertThat(target).contains("Printing").contains("Homeoffice").contains("printer");
        assertThat(UUID_RE.matcher(target).find())
                .as("audit target should read as names, not a raw UUID: " + target).isFalse();
    }

    @Test
    void delete_auditTarget_showsRoleAndSiteNamesNotRawIds() {
        Role role = persistRole("Printing");
        Site site = persistSite("Homeoffice", "10.30.0.0/16");
        String id = given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id, "resourceType", "printer"))
                .when().post("/api/v1/acl/type-grants")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/v1/acl/type-grants/" + id).then().statusCode(204);

        String target = latestTarget();
        assertThat(target).contains("Printing").contains("Homeoffice").contains("printer");
        assertThat(UUID_RE.matcher(target).find())
                .as("audit target should read as names, not a raw UUID: " + target).isFalse();
    }
}
