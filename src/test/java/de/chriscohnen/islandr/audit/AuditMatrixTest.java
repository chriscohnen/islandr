package de.chriscohnen.islandr.audit;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * For every mutating endpoint, assert that exactly the expected audit row(s)
 * land in the table. Failing this is a stronger signal than "the action
 * succeeded" — a 200 with no audit row would still pass functional tests
 * but violate PRD F-10.
 *
 * Strategy: snapshot the row count + the latest action keys before the call,
 * perform the call, then assert the diff. Avoids depending on absolute counts
 * across the whole suite (other tests write audit rows too).
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AuditMatrixTest {

    private String userId;

    @BeforeEach
    @Transactional
    void clearAndSeed() {
        AuditLog.deleteAll();
        // Local-admin (the AdminSessionExtension cookie) is in scope; create
        // one extra user we can target with admin-grant/delete actions.
        User u = User.createNew("Target User " + UUID.randomUUID(), "target-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        userId = u.id;
    }

    @Test
    void userCreate_writesAuditRow() {
        long before = AuditLog.count();
        given().contentType("application/json")
                .body("{\"name\":\"Mallory\",\"email\":\"mallory-" + UUID.randomUUID() + "@firma.de\"}")
                .when().post("/api/v1/users")
                .then().statusCode(201);
        assertThat(AuditLog.count()).isEqualTo(before + 1);
        assertThat(latestAction()).isEqualTo("user.create");
    }

    @Test
    void userAdminGrant_writesGrantAction() {
        given().contentType("application/json").body("{\"isAdmin\":true}")
                .when().put("/api/v1/users/" + userId + "/admin")
                .then().statusCode(200);
        assertThat(latestAction()).isEqualTo("user.admin_grant");
    }

    @Test
    void userAdminRevoke_writesRevokeAction() {
        // Promote first (one audit row), then demote (the row we assert on).
        given().contentType("application/json").body("{\"isAdmin\":true}")
                .when().put("/api/v1/users/" + userId + "/admin").then().statusCode(200);
        given().contentType("application/json").body("{\"isAdmin\":false}")
                .when().put("/api/v1/users/" + userId + "/admin").then().statusCode(200);
        assertThat(latestAction()).isEqualTo("user.admin_revoke");
    }

    @Test
    void userAdminNoChange_writesNothing() {
        // User is already non-admin. Setting it to false again is a no-op.
        long before = AuditLog.count();
        given().contentType("application/json").body("{\"isAdmin\":false}")
                .when().put("/api/v1/users/" + userId + "/admin")
                .then().statusCode(200);
        assertThat(AuditLog.count()).isEqualTo(before);
    }

    @Test
    void userDelete_writesDeleteAction() {
        given().when().delete("/api/v1/users/" + userId).then().statusCode(204);
        assertThat(latestAction()).isEqualTo("user.delete");
    }

    @Test
    void settingsUpdate_writesUpdateAction_andRedactsNoSecret() {
        var current = given().when().get("/api/v1/settings").then().statusCode(200).extract().jsonPath();
        // Touch one field so the diff is non-empty.
        String body = "{"
                + "\"wgSubnet\":\"" + current.getString("wgSubnet") + "\","
                + "\"wgServerPublicKey\":\"" + current.getString("wgServerPublicKey") + "\","
                + "\"wgServerEndpoint\":\"" + current.getString("wgServerEndpoint") + "\","
                + "\"wgClientAllowedIps\":\"" + current.getString("wgClientAllowedIps") + "\","
                + "\"wgClientDns\":\"10.99.99.99\","
                + "\"privateKeyRetention\":\"" + current.getString("privateKeyRetention") + "\","
                + "\"gravatarEnabled\":" + current.getBoolean("gravatarEnabled")
                + "}";
        given().contentType("application/json").body(body)
                .when().put("/api/v1/settings").then().statusCode(200);
        assertThat(latestAction()).isEqualTo("settings.update");
        // Meta JSON should mention the changed key.
        String meta = latestMeta();
        assertThat(meta).contains("wgClientDns");
    }

    @Test
    void oidcProviderUpdate_writesUpdateAction() {
        // Set credentials on microsoft — does not enable, so the action is
        // 'oidc_provider.update' (not '.enable'). clientSecret in the diff
        // must be redacted, never stored verbatim.
        given().contentType("application/json").body(
                "{\"clientId\":\"audit-test-client\",\"clientSecret\":\"audit-test-secret-12345\","
                + "\"tenantId\":\"" + UUID.randomUUID() + "\",\"allowedDomains\":\"firma.de\"}")
                .when().put("/api/v1/identity/providers/microsoft")
                .then().statusCode(200);
        assertThat(latestAction()).isEqualTo("oidc_provider.update");
        String meta = latestMeta();
        assertThat(meta).doesNotContain("audit-test-secret-12345");
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Most-recent audit action, excluding the {@code firewall.*} rows that
     * the ruleset hook appends after every ACL-mutating call. We're testing
     * "the mutation wrote the expected domain action"; the firewall apply
     * is a separate concern covered by FirewallTest.
     */
    @Transactional
    String latestAction() {
        AuditLog row = AuditLog.<AuditLog>find(
                "action not like ?1 order by createdAt desc, id",
                "firewall.%").firstResult();
        return row == null ? null : row.action;
    }

    @Transactional
    String latestMeta() {
        AuditLog row = AuditLog.<AuditLog>find(
                "action not like ?1 order by createdAt desc, id",
                "firewall.%").firstResult();
        return row == null ? null : row.metaJson;
    }
}
