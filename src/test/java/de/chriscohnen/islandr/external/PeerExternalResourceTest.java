package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST-level tests for the external peer facade (issue #15, ADR-0026).
 * Split into two auth stories: the {@link AdminSessionExtension}-backed
 * class proves the facade works at all (business logic, DTO shape); the
 * bearer-token-specific tests prove the actual point of this endpoint — a
 * caller with no browser session, only an API key, can use it.
 */
@QuarkusTest
class PeerExternalResourceTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @Transactional
    void reset() {
        ApiKey.deleteAll();
        Peer.delete("name like ?1", "external-api-test-%");
    }

    @Test
    void bearerToken_withoutAnySession_canListAndCreatePeers() {
        String rawKey = apiKeys.create("test-key", "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/peers")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + rawKey)
                .contentType("application/json")
                .body("""
                        { "name": "external-api-test-site", "assignedIp": "10.8.0.90",
                          "type": "site", "siteAllowedCidrs": "192.168.90.0/24" }
                        """)
                .when().post("/api/external/v1/peers")
                .then().statusCode(201)
                .body("peer.type", equalTo("site"))
                .body("peer.name", equalTo("external-api-test-site"));
    }

    @Test
    void bearerToken_revokedKey_rejected() {
        ApiKeyService.CreateResult r = apiKeys.create("test-key", "admin");
        apiKeys.revoke(r.apiKey().id, "admin");

        given().header("Authorization", "Bearer " + r.rawKey())
                .when().get("/api/external/v1/peers")
                .then().statusCode(401);
    }

    @Test
    void bearerToken_garbage_rejected() {
        given().header("Authorization", "Bearer not-a-real-key")
                .when().get("/api/external/v1/peers")
                .then().statusCode(401);
    }

    @Test
    void noCredentials_rejected() {
        given().when().get("/api/external/v1/peers").then().statusCode(401);
    }

    @org.junit.jupiter.api.Nested
    @ExtendWith(AdminSessionExtension.class)
    class ViaAdminSession {

        @Test
        void create_clientPeer_requiresUserId() {
            given().contentType("application/json")
                    .body("""
                            { "name": "external-api-test-client", "assignedIp": "10.8.0.91" }
                            """)
                    .when().post("/api/external/v1/peers")
                    .then().statusCode(400);
        }

        @Test
        void list_returnsCreatedSitePeer() {
            given().contentType("application/json")
                    .body("""
                            { "name": "external-api-test-site2", "assignedIp": "10.8.0.92",
                              "type": "site", "siteAllowedCidrs": "192.168.92.0/24" }
                            """)
                    .when().post("/api/external/v1/peers")
                    .then().statusCode(201);

            given().when().get("/api/external/v1/peers")
                    .then().statusCode(200)
                    .body("findAll { it.name == 'external-api-test-site2' }", hasSize(1));
        }
    }
}
