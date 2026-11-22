package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** edge-status-plan-fields (ADR-I-0005 §5): the status report carries what the agent applied. */
@QuarkusTest
class EdgeStatusPlanFieldsTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        ApiKey.deleteAll();
        EdgeHub.deleteAll();
    }

    @Transactional
    EdgeHub edge() {
        EdgeHub e = EdgeHub.createNew("plan-fields-" + UUID.randomUUID().toString().substring(0, 8), 1.0, 1.0, null);
        e.persist();
        return e;
    }

    @Transactional
    EdgeHub reload(String id) { return EdgeHub.findById(id); }

    private void report(EdgeHub e, String key, Map<String, Object> body) {
        given().header("Authorization", "Bearer " + key).contentType(ContentType.JSON).body(body)
                .post("/api/external/v1/edge/" + e.id + "/status").then().statusCode(200);
    }

    @Test
    void appliedPlanFields_areStored_andALegacyReportStillWorks() {
        EdgeHub e = edge();
        String key = apiKeys.create("k", Set.of(ApiKeyScope.TELEMETRY_WRITE), e.id, "admin").rawKey();
        Instant applied = Instant.now().minusSeconds(30);

        report(e, key, Map.of("peers", java.util.List.of(), "planSerial", 7, "appliedAt", applied.toString(),
                "skippedPeers", 2, "agentVersion", "0.1.0"));
        EdgeHub r = reload(e.id);
        assertThat(r.lastPlanSerial).isEqualTo(7);
        assertThat(r.lastAppliedAt.toEpochMilli()).isEqualTo(applied.toEpochMilli());
        assertThat(r.skippedPeers).isEqualTo(2);
        assertThat(r.agentVersion).isEqualTo("0.1.0");
        assertThat(r.planRejected).isNull();
        assertThat(r.lastSyncAt).isNotNull();

        report(e, key, Map.of("peers", java.util.List.of(), "planSerial", 8, "planRejected", "unknown proto 'sctp'"));
        r = reload(e.id);
        assertThat(r.planRejected).isEqualTo("unknown proto 'sctp'");
        assertThat(r.lastPlanSerial).isEqualTo(8);

        // an old agent: peers only. Sync time moves, the rest is left alone; a rejection stays visible.
        Instant before = r.lastSyncAt;
        report(e, key, Map.of("peers", java.util.List.of()));
        r = reload(e.id);
        assertThat(r.lastSyncAt).isAfterOrEqualTo(before);
        assertThat(r.lastPlanSerial).isEqualTo(8);
        assertThat(r.agentVersion).isEqualTo("0.1.0");
    }

    @Test
    void aReportThatAppliedCleanly_clearsAnEarlierRejection() {
        EdgeHub e = edge();
        String key = apiKeys.create("k", Set.of(ApiKeyScope.TELEMETRY_WRITE), e.id, "admin").rawKey();
        report(e, key, Map.of("peers", java.util.List.of(), "planSerial", 1, "planRejected", "bad"));
        report(e, key, Map.of("peers", java.util.List.of(), "planSerial", 2, "appliedAt", Instant.now().toString()));
        assertThat(reload(e.id).planRejected).isNull();
    }
}
