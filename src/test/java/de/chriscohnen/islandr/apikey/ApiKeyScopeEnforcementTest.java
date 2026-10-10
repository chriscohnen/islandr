package de.chriscohnen.islandr.apikey;

import de.chriscohnen.islandr.auth.SessionFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * The two enforcement layers apikey-scopes adds on top of what was, in v1,
 * an unconditionally full-admin-equivalent credential (ADR-0026, R-184):
 *
 * <ol>
 *   <li>The facade boundary ({@link ApiKeyAuthFilter}) — a key without
 *       {@code full} cannot reach anything outside {@code /api/external/v1/}
 *       except the one console endpoint a {@code config:*} scope names.</li>
 *   <li>Per-operation scopes inside that boundary ({@code Auth.requireScope},
 *       exercised here through {@link de.chriscohnen.islandr.external.PeerExternalResource}
 *       as a representative facade endpoint) — a read-scoped key gets 403 on
 *       a write.</li>
 * </ol>
 */
@QuarkusTest
class ApiKeyScopeEnforcementTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        ApiKey.deleteAll();
    }

    private String keyWith(String... scopes) {
        return apiKeys.create("scope-test-" + UUID.randomUUID(), Set.of(scopes), "admin").rawKey();
    }

    // ---- Per-operation scopes inside the facade ----------------------------

    @Test
    void readScopedKey_canListPeers() {
        String key = keyWith(ApiKeyScope.PEERS_READ);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/peers")
                .then().statusCode(200);
    }

    @Test
    void readScopedKey_cannotCreateAPeer() {
        String key = keyWith(ApiKeyScope.PEERS_READ);
        given().header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .body("{\"type\":\"site\",\"name\":\"scope-test-site\",\"assignedIp\":\"10.8.0.230\",\"siteAllowedCidrs\":\"192.168.93.0/24\"}")
                .when().post("/api/external/v1/peers")
                .then().statusCode(403);
    }

    @Test
    void writeScopedKeyAlone_cannotListPeers() {
        // peers:write without peers:read — the two are independent grants,
        // not a ladder where write implies read.
        String key = keyWith(ApiKeyScope.PEERS_WRITE);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/peers")
                .then().statusCode(403);
    }

    @Test
    void fullScopedKey_canDoBoth() {
        String key = keyWith(ApiKeyScope.FULL);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/peers")
                .then().statusCode(200);
    }

    // ---- Facade boundary: outside /api/external/v1/ -------------------------

    @Test
    void nonFullKey_cannotReachTheInternalConsoleApi() {
        String key = keyWith(ApiKeyScope.PEERS_READ);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/v1/peers")
                .then().statusCode(403);
    }

    @Test
    void fullKey_canStillReachTheInternalConsoleApi() {
        // Grandfathering: the pre-scoping key shape ("full" reaches
        // everything) must keep working exactly as before.
        String key = keyWith(ApiKeyScope.FULL);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/v1/peers")
                .then().statusCode(200);
    }

    // ---- The config:export/config:import carve-out --------------------------

    @Test
    void configExportScopedKey_reachesExactlyThatConsoleEndpoint() {
        String key = keyWith(ApiKeyScope.CONFIG_EXPORT);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/v1/admin/config/export")
                .then().statusCode(200);
    }

    @Test
    void configExportScopedKey_stillCannotReachOtherConsoleEndpoints() {
        String key = keyWith(ApiKeyScope.CONFIG_EXPORT);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/v1/peers")
                .then().statusCode(403);
    }

    @Test
    void keyWithoutConfigExportScope_cannotReachConfigExport() {
        String key = keyWith(ApiKeyScope.PEERS_READ);
        given().header("Authorization", "Bearer " + key)
                .when().get("/api/v1/admin/config/export")
                .then().statusCode(403);
    }

    // ---- Audit log names the key, not a generic "apikey:" -------------------

    @Test
    void writeAction_namesTheIssuingKeyInTheAuditLog() {
        String uniqueLabel = "scope-audit-" + UUID.randomUUID();
        String key = apiKeys.create(uniqueLabel, Set.of(ApiKeyScope.PEERS_WRITE, ApiKeyScope.PEERS_READ), "admin").rawKey();

        given().header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .body("{\"type\":\"site\",\"name\":\"audit-test-site-" + UUID.randomUUID()
                        + "\",\"assignedIp\":\"10.8.0.231\",\"siteAllowedCidrs\":\"192.168.95.0/24\"}")
                .when().post("/api/external/v1/peers")
                .then().statusCode(201);

        // Session-admin read, deliberately not the bearer-token path used
        // above: this key has no audit:read scope, and mixing a session
        // cookie onto the same RestAssured call as the Bearer header would
        // leave which filter wins ambiguous instead of testing one thing.
        given().cookie(SessionFilter.COOKIE_NAME, adminSessionCookie())
                .when().get("/api/v1/audit?action=peer.create&limit=5")
                .then().statusCode(200)
                .body("", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .body("actor", org.hamcrest.Matchers.hasItem(containsString(uniqueLabel)));
    }

    private static String adminSessionCookie() {
        var resp = given().contentType("application/json")
                .body("{\"username\":\"admin\",\"password\":\"test-admin-pw\"}")
                .when().post("/api/v1/auth/login");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("test admin login failed (status=" + resp.statusCode() + ")");
        }
        return resp.getDetailedCookie(SessionFilter.COOKIE_NAME).getValue();
    }
}
