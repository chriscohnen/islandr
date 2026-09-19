package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * An access review asks two questions — who could reach what, and who changed
 * it. Both answers exist inside Islandr and neither was retrievable from
 * outside: {@code /roles} returned a grant *count*, and the audit log was
 * reachable only with a session cookie.
 */
@QuarkusTest
class GrantsAuditExternalTest {

    @Inject ApiKeyService apiKeys;
    @Inject AuditService audit;

    private String key;

    @BeforeEach
    void setUp() {
        wipeKeys();
        key = apiKeys.create("reporting", "admin").rawKey();
    }

    @Transactional
    void wipeKeys() {
        de.chriscohnen.islandr.apikey.ApiKey.deleteAll();
    }

    @Test
    void grantsAreListedWithSubjectResourceAndKind() {
        String suffix = seedRoleGrant();

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/grants")
                .then().statusCode(200)
                .body("findAll { it.resourceName == 'ExtRes-" + suffix + "' }.kind", hasItem("role"))
                .body("findAll { it.resourceName == 'ExtRes-" + suffix + "' }.subjectType", hasItem("user"))
                .body("findAll { it.resourceName == 'ExtRes-" + suffix + "' }.allPorts", hasItem(true));
    }

    @Test
    void grants_noCredentials_rejected() {
        given().when().get("/api/external/v1/grants").then().statusCode(401);
    }

    @Test
    void auditEntriesAreListedNewestFirst() {
        String action = "test.reporting." + UUID.randomUUID().toString().substring(0, 8);
        writeAudit(action);

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/audit?action=" + action)
                .then().statusCode(200)
                .body("", hasSize(greaterThanOrEqualTo(1)))
                .body("action", everyItem(is(action)));
    }

    @Test
    void auditLimitIsHonoured() {
        String action = "test.limit." + UUID.randomUUID().toString().substring(0, 8);
        writeAudit(action);
        writeAudit(action);
        writeAudit(action);

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/audit?action=" + action + "&limit=2")
                .then().statusCode(200)
                .body("", hasSize(2));
    }

    @Test
    void audit_noCredentials_rejected() {
        given().when().get("/api/external/v1/audit").then().statusCode(401);
    }

    @Test
    void audit_invalidBeforeTimestamp_isRejected() {
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/audit?before=yesterday")
                .then().statusCode(400);
    }

    @Transactional
    String seedRoleGrant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = User.createNew("ExtGrant " + suffix, "extgrant-" + suffix + "@firma.de");
        u.persist();
        Site site = Site.createNew("ExtSite-" + suffix, "10.64.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "ExtRes-" + suffix, "10.64.0.5", null, "computer");
        res.persist();
        Role everyone = Role.find("autoAll", true).firstResult();
        RoleResourceGrant.createNew(everyone.id, res.id, true).persist();
        return suffix;
    }

    @Transactional
    void writeAudit(String action) {
        audit.logEvent("tester", action, "Target:" + UUID.randomUUID(), Map.of("k", "v"));
    }

    @Transactional
    long auditCount(String action) {
        return AuditLog.count("action", action);
    }
}
