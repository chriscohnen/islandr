package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * edge-status-report (loop/TASK.md, ADR-I-0002): the only way a peer
 * connected to an edge, not the central hub, leaves "Disconnected" on the
 * dashboard. Reuses {@code ActivityPoller#applySample} — same update, same
 * rules as the central hub's own {@code wg show dump} poll — gated by the
 * same per-edge authorization {@code EdgePskExposureTest} already covers,
 * plus the per-reported-peer "is this public key actually homed here" check
 * this task adds on top.
 *
 * Deliberately NOT @ExtendWith(AdminSessionExtension) — see
 * EdgePskExposureTest's class comment for why that would ride along on this
 * file's own Bearer-authenticated calls and mask a scope/edge mismatch.
 */
@QuarkusTest
class EdgeStatusReportTest {

    @Inject ApiKeyService apiKeys;
    @Inject EntityManager em;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        ApiKey.deleteAll();
        Peer.deleteAll();
        UserResourceGrant.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        EdgeHub.deleteAll();
        User.delete("email like ?1", "statushome-%@example.test");
    }

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    @Transactional
    EdgeHub createEdgeHub(String name) {
        EdgeHub e = EdgeHub.createNew(name, 1.0, 1.0, null);
        e.persist();
        return e;
    }

    @Transactional
    Site createSite(String name, String cidr, String edgeHubId) {
        Site s = Site.createNew(name, cidr, null);
        s.edgeHubId = edgeHubId;
        s.persist();
        return s;
    }

    @Transactional
    User createUser() {
        User u = User.createNew("Status Home " + uniq(), "statushome-" + uniq() + "@example.test");
        u.persist();
        return u;
    }

    @Transactional
    Resource createResource(String siteId) {
        Resource r = Resource.createNew(siteId, "res-" + uniq(), "10.97.0.5", null, "computer");
        r.persist();
        return r;
    }

    @Transactional
    void grantAllPorts(String userId, String resourceId) {
        UserResourceGrant.createNew(userId, resourceId, true).persist();
    }

    private String adminCookie;

    String nextIp() {
        if (adminCookie == null) {
            var resp = given().contentType("application/json")
                    .body("{\"username\":\"admin\",\"password\":\"test-admin-pw\"}")
                    .when().post("/api/v1/auth/login");
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("test admin login failed: " + resp.statusCode());
            }
            adminCookie = resp.getDetailedCookie(SessionFilter.COOKIE_NAME).getValue();
        }
        return given().cookie(SessionFilter.COOKIE_NAME, adminCookie)
                .when().get("/api/v1/peers/next-ip")
                .then().statusCode(200).extract().path("assignedIp");
    }

    @Transactional
    Peer persistPeer(String userId, String ip, String publicKey) {
        Peer p = Peer.createNew(userId, "peer-" + uniq(), publicKey, ip);
        p.persist();
        return p;
    }

    @Transactional
    Peer reload(String id) {
        return Peer.findById(id);
    }

    @Test
    void create_rejectsTelemetryWriteWithoutAnEdgeHubId() {
        assertThatThrownBy(() -> apiKeys.create("no-edge-" + uniq(), Set.of(ApiKeyScope.TELEMETRY_WRITE), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void endpoint_appliesTheSampleForAPeerHomedAtThisEdge() {
        EdgeHub edge = createEdgeHub("Status-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.97.0.0/24", edge.id);
        Resource res = createResource(site.id);
        User user = createUser();
        grantAllPorts(user.id, res.id);
        String pubKey = "PUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Peer peer = persistPeer(user.id, nextIp(), pubKey);
        String rawKey = apiKeys.create("status-" + uniq(), Set.of(ApiKeyScope.TELEMETRY_WRITE), edge.id, "admin").rawKey();

        Instant handshake = Instant.now().minusSeconds(5);
        given().header("Authorization", "Bearer " + rawKey).contentType(ContentType.JSON)
                .body(Map.of("peers", java.util.List.of(Map.of(
                        "publicKey", pubKey,
                        "lastHandshake", handshake.toString(),
                        "endpoint", "203.0.113.9:51820",
                        "rxBytes", 1000,
                        "txBytes", 2000
                ))))
                .when().post("/api/external/v1/edge/" + edge.id + "/status")
                .then().statusCode(200)
                .body("accepted", is(1))
                .body("rejected", empty());

        Peer updated = reload(peer.id);
        // Millisecond precision only — the column (and the JSON round-trip)
        // truncates past that, same as every other Instant field this
        // codebase persists.
        assertThat(updated.lastSeenAt.toEpochMilli()).isEqualTo(handshake.toEpochMilli());
        assertThat(updated.lastSeenEndpoint).isEqualTo("203.0.113.9:51820");
        assertThat(updated.totalRxBytes).isEqualTo(1000);
        assertThat(updated.totalTxBytes).isEqualTo(2000);
    }

    @Test
    void endpoint_accumulatesByteDeltasAcrossTwoReports_sameAsThePollerWould() {
        EdgeHub edge = createEdgeHub("Delta-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.97.1.0/24", edge.id);
        Resource res = createResource(site.id);
        User user = createUser();
        grantAllPorts(user.id, res.id);
        String pubKey = "PUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Peer peer = persistPeer(user.id, nextIp(), pubKey);
        String rawKey = apiKeys.create("delta-" + uniq(), Set.of(ApiKeyScope.TELEMETRY_WRITE), edge.id, "admin").rawKey();

        report(rawKey, edge.id, pubKey, Instant.now().minusSeconds(10), 1000, 500);
        report(rawKey, edge.id, pubKey, Instant.now(), 1500, 900);

        Peer updated = reload(peer.id);
        assertThat(updated.totalRxBytes).isEqualTo(1500);
        assertThat(updated.totalTxBytes).isEqualTo(900);
    }

    private void report(String rawKey, String edgeId, String pubKey, Instant handshake, long rx, long tx) {
        given().header("Authorization", "Bearer " + rawKey).contentType(ContentType.JSON)
                .body(Map.of("peers", java.util.List.of(Map.of(
                        "publicKey", pubKey, "lastHandshake", handshake.toString(), "rxBytes", rx, "txBytes", tx))))
                .when().post("/api/external/v1/edge/" + edgeId + "/status")
                .then().statusCode(200);
    }

    @Test
    void endpoint_rejectsAndAuditsAPeerNotHomedAtThisEdge() {
        EdgeHub edge = createEdgeHub("Foreign-" + uniq());
        // Unrelated user/peer with no grant on any resource behind this edge.
        User outsider = createUser();
        String pubKey = "PUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Peer peer = persistPeer(outsider.id, nextIp(), pubKey);
        String rawKey = apiKeys.create("foreign-" + uniq(), Set.of(ApiKeyScope.TELEMETRY_WRITE), edge.id, "admin").rawKey();
        long before = auditCount();

        given().header("Authorization", "Bearer " + rawKey).contentType(ContentType.JSON)
                .body(Map.of("peers", java.util.List.of(Map.of(
                        "publicKey", pubKey, "lastHandshake", Instant.now().toString(),
                        "rxBytes", 1, "txBytes", 1))))
                .when().post("/api/external/v1/edge/" + edge.id + "/status")
                .then().statusCode(200)
                .body("accepted", is(0))
                .body("rejected", contains(pubKey));

        assertThat(reload(peer.id).lastSeenAt).isNull();
        assertThat(auditCount()).isEqualTo(before + 1);
    }

    @Test
    void endpoint_rejectsAnUnknownPublicKey() {
        EdgeHub edge = createEdgeHub("Unknown-" + uniq());
        String rawKey = apiKeys.create("unknown-" + uniq(), Set.of(ApiKeyScope.TELEMETRY_WRITE), edge.id, "admin").rawKey();
        String ghostKey = "PUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=";

        given().header("Authorization", "Bearer " + rawKey).contentType(ContentType.JSON)
                .body(Map.of("peers", java.util.List.of(Map.of(
                        "publicKey", ghostKey, "lastHandshake", Instant.now().toString(),
                        "rxBytes", 1, "txBytes", 1))))
                .when().post("/api/external/v1/edge/" + edge.id + "/status")
                .then().statusCode(200)
                .body("accepted", is(0))
                .body("rejected", contains(ghostKey));
    }

    @Test
    void endpoint_refusesAKeyBoundToADifferentEdge() {
        EdgeHub home = createEdgeHub("Home2-" + uniq());
        EdgeHub other = createEdgeHub("Other2-" + uniq());
        String rawKey = apiKeys.create("wrong-edge2-" + uniq(), Set.of(ApiKeyScope.TELEMETRY_WRITE), home.id, "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey).contentType(ContentType.JSON)
                .body(Map.of("peers", java.util.List.of()))
                .when().post("/api/external/v1/edge/" + other.id + "/status")
                .then().statusCode(403);
    }

    @Transactional
    long auditCount() {
        return AuditLog.count("action", "edge.status_report_rejected");
    }
}
