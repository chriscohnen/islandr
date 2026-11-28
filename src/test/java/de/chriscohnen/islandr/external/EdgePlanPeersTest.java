package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * edge-plan-peers (ADR-I-0005): the peers part of the plan. Like the PSK test,
 * no admin session here — it would bypass the per-edge binding under test.
 */
@QuarkusTest
class EdgePlanPeersTest {

    @Inject ApiKeyService apiKeys;

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        ApiKey.deleteAll();
        Peer.deleteAll();
        EdgeHub.deleteAll();
        User.delete("email like ?1", "plan-%@example.test");
    }

    private static String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    @Transactional
    EdgeHub edge(String name) {
        EdgeHub e = EdgeHub.createNew(name + uniq(), 1.0, 1.0, null);
        e.persist();
        return e;
    }

    @Transactional
    String userId() {
        User u = User.createNew("Plan " + uniq(), "plan-" + uniq() + "@example.test");
        u.persist();
        return u.id;
    }

    @Transactional
    String peer(String userId, String edgeId, String ip, String psk, boolean enabled) {
        Peer p = Peer.createNew(userId, "p-" + uniq(), "PK" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", ip);
        p.edgeHubId = edgeId;
        p.presharedKey = psk;
        p.enabled = enabled;
        p.persist();
        return p.id;
    }

    private String key(EdgeHub e, String scope) {
        return apiKeys.create("plan-" + uniq(), Set.of(scope), e.id, "admin").rawKey();
    }

    @Test
    void returnsOnlyThisEdgesEnabledPeers_withoutPsks() {
        EdgeHub chile = edge("chile-");
        EdgeHub other = edge("other-");
        String u = userId();
        String mine = peer(u, chile.id, "10.8.7.1", "PSKSECRETAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", true);
        peer(u, chile.id, "10.8.7.2", null, false);      // disabled: not in the plan
        peer(u, other.id, "10.8.7.3", null, true);       // another edge's
        peer(u, null, "10.8.7.4", null, true);           // central hub's

        given().header("Authorization", "Bearer " + key(chile, ApiKeyScope.PLAN_READ))
                .when().get("/api/external/v1/edge/" + chile.id + "/plan")
                .then().statusCode(200)
                .body("planVersion", is(1))
                .body("edge.id", is(chile.id))
                .body("peers", hasSize(1))
                .body("peers[0].id", is(mine))
                .body("peers[0].allowedIps", contains("10.8.7.1/32"))
                .body("peers[0].hasPresharedKey", is(true))
                .body("peers[0].presharedKey", nullValue())
                .body(not(containsString("PSKSECRET")));
    }

    @Test
    void serialStaysWhileNothingChanges_andRisesWhenAPeerIsAdded() {
        EdgeHub e = edge("serial-");
        String u = userId();
        peer(u, e.id, "10.8.8.1", null, true);
        String k = key(e, ApiKeyScope.PLAN_READ);

        int first = given().header("Authorization", "Bearer " + k)
                .get("/api/external/v1/edge/" + e.id + "/plan").then().statusCode(200).extract().path("serial");
        given().header("Authorization", "Bearer " + k)
                .get("/api/external/v1/edge/" + e.id + "/plan").then().statusCode(200)
                .body("serial", is(first)).body("peers", hasSize(1));

        peer(u, e.id, "10.8.8.2", null, true);
        given().header("Authorization", "Bearer " + k)
                .get("/api/external/v1/edge/" + e.id + "/plan").then().statusCode(200)
                .body("serial", is(first + 1)).body("peers", hasSize(2));
    }

    @Test
    void auditLog_getsOneEntryPerChangedPlan_notOnePerFetch() {
        EdgeHub e = edge("audit-");
        String u = userId();
        String k = key(e, ApiKeyScope.PLAN_READ);
        long before = planAudits(e.id);

        for (int i = 0; i < 4; i++) {
            given().header("Authorization", "Bearer " + k)
                    .get("/api/external/v1/edge/" + e.id + "/plan").then().statusCode(200);
        }
        // the first fetch hands out serial 1 (a change from "never fetched"); the repeats nothing new
        assertThat(planAudits(e.id)).isEqualTo(before + 1);

        peer(u, e.id, "10.8.9.1", null, true);
        given().header("Authorization", "Bearer " + k).get("/api/external/v1/edge/" + e.id + "/plan").then().statusCode(200);
        given().header("Authorization", "Bearer " + k).get("/api/external/v1/edge/" + e.id + "/plan").then().statusCode(200);
        assertThat(planAudits(e.id)).isEqualTo(before + 2);
    }

    @Transactional
    long planAudits(String edgeId) {
        return de.chriscohnen.islandr.audit.AuditLog.count("action = ?1 and target like ?2",
                "edge.plan_updated", "%" + edgeId + "%");
    }

    @Test
    void aKeyBoundToAnotherEdge_orWithoutTheScope_isRefused() {
        EdgeHub a = edge("a-");
        EdgeHub b = edge("b-");
        given().header("Authorization", "Bearer " + key(b, ApiKeyScope.PLAN_READ))
                .get("/api/external/v1/edge/" + a.id + "/plan").then().statusCode(403);
        given().header("Authorization", "Bearer " + key(a, ApiKeyScope.PSKS_READ))
                .get("/api/external/v1/edge/" + a.id + "/plan").then().statusCode(403);
        given().get("/api/external/v1/edge/" + a.id + "/plan").then().statusCode(401);
    }

    @Test
    void planReadWithoutAnEdgeBinding_cannotBeCreated() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> apiKeys.create("x-" + uniq(), Set.of(ApiKeyScope.PLAN_READ), "admin"))
                .isInstanceOf(jakarta.ws.rs.BadRequestException.class);
    }
}
