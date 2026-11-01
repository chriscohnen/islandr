package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/** monitoring-status-endpoint (#71): install-wide gauges, read-only scope, nothing per peer. */
@QuarkusTest
class MonitoringExternalResourceTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @Transactional
    void wipeKeys() {
        ApiKey.deleteAll();
    }

    @Test
    void monitoringKey_getsTheGauges_inPrometheusTextFormat() {
        String key = apiKeys.create("prom", Set.of(ApiKeyScope.MONITORING_READ), "admin").rawKey();

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/metrics")
                .then().statusCode(200)
                .contentType(containsString("text/plain"))
                .body(containsString("# TYPE islandr_enforcement_ok gauge"))
                .body(containsString("islandr_boot_table_active "))
                .body(containsString("islandr_peers_connected "));
    }

    @Test
    void exposesNothingPerPeer() {
        String key = apiKeys.create("prom", Set.of(ApiKeyScope.MONITORING_READ), "admin").rawKey();

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/metrics")
                .then().statusCode(200)
                .body(not(containsString("{")));
    }

    @Test
    void keyWithAnotherScope_isForbidden() {
        String key = apiKeys.create("peers", Set.of(ApiKeyScope.PEERS_READ), "admin").rawKey();

        given().header("Authorization", "Bearer " + key)
                .when().get("/api/external/v1/metrics")
                .then().statusCode(403);
    }

    @Test
    void withoutCredentials_isRejected() {
        given().when().get("/api/external/v1/metrics").then().statusCode(401);
    }
}
