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
 * REST-level CRUD for {@code /api/v1/acl/network-grants} — whole-network role
 * grants (#78, ADR-0029). Enforcement expansion (RuleBuilder) has its own
 * test in {@code FirewallTest}; this covers the CRUD surface only.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AclNetworkGrantResourceTest {

    @Inject RoleBootstrap roleBootstrap;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        Role.delete("name like ?1", "Netzwerkverwalter%");
        // Match only this test's own bare "Homeoffice"/"HomeofficeN" fixtures —
        // NOT "Homeoffice-<uuid>", which DnsQueryHandlerTest seeds (with a
        // hyphenated suffix) and never cleans up. A loose "Homeoffice%" would
        // eventually try to delete those leftover rows too and hit the same
        // FK-constraint failure this fix is meant to eliminate, since their
        // Resource children lack ON DELETE CASCADE to sites (see V9/V13/V39).
        Site.delete("name = ?1 or name like ?2", "Homeoffice", "Homeoffice_");
        AuditLog.deleteAll();
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
        Role role = persistRole("Netzwerkverwalter");
        Site site = persistSite("Homeoffice", "10.30.0.0/16");

        given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id))
                .when().post("/api/v1/acl/network-grants")
                .then().statusCode(201)
                .body("roleId", equalTo(role.id))
                .body("siteId", equalTo(site.id))
                .body("siteName", equalTo("Homeoffice"));

        given().when().get("/api/v1/acl/network-grants")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].siteName", equalTo("Homeoffice"));
    }

    @Test
    void create_sameRuleTwice_isIdempotentNotDuplicated() {
        Role role = persistRole("Netzwerkverwalter2");
        Site site = persistSite("Homeoffice2", "10.30.0.0/16");
        Map<String, String> body = Map.of("roleId", role.id, "siteId", site.id);

        given().contentType("application/json").body(body)
                .when().post("/api/v1/acl/network-grants").then().statusCode(201);
        given().contentType("application/json").body(body)
                .when().post("/api/v1/acl/network-grants").then().statusCode(201);

        given().when().get("/api/v1/acl/network-grants")
                .then().statusCode(200).body("$", hasSize(1));
    }

    @Test
    void create_unknownRole_returns404() {
        Site site = persistSite("Homeoffice3", "10.30.0.0/16");
        given().contentType("application/json")
                .body(Map.of("roleId", "not-a-real-role", "siteId", site.id))
                .when().post("/api/v1/acl/network-grants")
                .then().statusCode(404);
    }

    @Test
    void create_unknownSite_returns404() {
        Role role = persistRole("Netzwerkverwalter3");
        given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", "not-a-real-site"))
                .when().post("/api/v1/acl/network-grants")
                .then().statusCode(404);
    }

    @Test
    void delete_removesTheGrant() {
        Role role = persistRole("Netzwerkverwalter4");
        Site site = persistSite("Homeoffice4", "10.30.0.0/16");
        String id = given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id))
                .when().post("/api/v1/acl/network-grants")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/v1/acl/network-grants/" + id).then().statusCode(204);
        given().when().get("/api/v1/acl/network-grants").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void delete_unknownId_returns404() {
        given().when().delete("/api/v1/acl/network-grants/not-a-real-id").then().statusCode(404);
    }

    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Test
    void create_auditTarget_showsRoleAndSiteNamesNotRawIds() {
        Role role = persistRole("Netzwerkverwalter5");
        Site site = persistSite("Homeoffice5", "10.30.0.0/16");

        given().contentType("application/json")
                .body(Map.of("roleId", role.id, "siteId", site.id))
                .when().post("/api/v1/acl/network-grants").then().statusCode(201);

        String target = latestTarget();
        assertThat(target).contains("Netzwerkverwalter5").contains("Homeoffice5");
        assertThat(UUID_RE.matcher(target).find())
                .as("audit target should read as names, not a raw UUID: " + target).isFalse();
    }
}
