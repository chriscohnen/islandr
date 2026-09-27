package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
    @Inject EntityManager em;
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

    /**
     * A port-limited grant has to be readable by a machine. Until 1.0 the only
     * port information here was {@code ports}, a list of display labels built
     * for the console ("SSH 22") — no port id, and crucially no transport,
     * because the label's protocol part is the UI's application label. A
     * consumer building a firewall rule could not tell tcp from udp, so only
     * allPorts grants were usable at all.
     */
    @Test
    void portLimitedGrantCarriesTransportAndRangeWithoutParsingALabel() {
        String suffix = seedPortLimitedGrant();
        String mine = "findAll { it.resourceName == 'ExtPortRes-" + suffix + "' }";

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/grants")
                .then().statusCode(200)
                .body(mine + ".allPorts", hasItem(false))
                .body(mine + ".portDetails.flatten().transport", hasItem("udp"))
                .body(mine + ".portDetails.flatten().port", hasItem(51820))
                .body(mine + ".portDetails.flatten().portEnd", hasItem(51830))
                .body(mine + ".portDetails.flatten().protocol", hasItem("CUSTOM"));
    }

    /** The console still reads the labels — they must not disappear. */
    @Test
    void portLimitedGrantStillCarriesTheDisplayLabels() {
        String suffix = seedPortLimitedGrant();

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/grants")
                .then().statusCode(200)
                .body("findAll { it.resourceName == 'ExtPortRes-" + suffix + "' }.ports.flatten()",
                        hasItem("CUSTOM 51820-51830"));
    }

    @Transactional
    String seedPortLimitedGrant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = User.createNew("ExtPort " + suffix, "extport-" + suffix + "@firma.de");
        u.persist();
        Site site = Site.createNew("ExtPortSite-" + suffix, "10.65.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "ExtPortRes-" + suffix, "10.65.0.5", null, "computer");
        res.persist();
        ResourcePort port = ResourcePort.createNew(
                res.id, 51820, 51830, "udp", "CUSTOM", "Tunnel", null, false, false, null);
        port.persist();
        Role everyone = Role.find("autoAll", true).firstResult();
        RoleResourceGrant grant = RoleResourceGrant.createNew(everyone.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        return suffix;
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
