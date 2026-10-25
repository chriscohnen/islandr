package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.acl.AclResolutionService;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * edge-psk-exposure (loop/TASK.md, ADR-I-0002 Vertrauensmodell): the first
 * API-key scope bound to one object (an {@link EdgeHub}), not the whole
 * install. Covers the binding requirement at creation time, the per-edge
 * authorization at read time, and that only PSK-bearing peers actually
 * homed at that edge come back.
 */
/*
 * Deliberately NOT @ExtendWith(AdminSessionExtension) — that pins an admin
 * session cookie as the RestAssured default for every request, which would
 * ride along on this file's own "Bearer <edge key>" calls and let the
 * session (always scope-exempt, see AuthContext#hasScope) silently
 * override the per-edge restriction these tests exist to check. Per that
 * extension's own doc comment: send the admin cookie explicitly instead,
 * only on the one helper that actually needs it (nextIp).
 */
@QuarkusTest
class EdgePskExposureTest {

    @Inject ApiKeyService apiKeys;
    @Inject AclResolutionService acl;
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
        User.delete("email like ?1", "pskhome-%@example.test");
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
        User u = User.createNew("Psk Home " + uniq(), "pskhome-" + uniq() + "@example.test");
        u.persist();
        return u;
    }

    @Transactional
    Resource createResource(String siteId) {
        Resource r = Resource.createNew(siteId, "res-" + uniq(), "10.98.0.5", null, "computer");
        r.persist();
        return r;
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
    String persistPeer(String userId, String ip, String presharedKey) {
        Peer p = Peer.createNew(userId, "peer-" + uniq(),
                "PUBKEY" + uniq() + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", ip);
        p.presharedKey = presharedKey;
        p.persist();
        return p.id;
    }

    @Test
    void create_rejectsPsksReadWithoutAnEdgeHubId() {
        assertThatThrownBy(() -> apiKeys.create("no-edge-" + uniq(), Set.of(ApiKeyScope.PSKS_READ), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_rejectsPsksReadForAnUnknownEdgeHubId() {
        assertThatThrownBy(() -> apiKeys.create("bad-edge-" + uniq(),
                Set.of(ApiKeyScope.PSKS_READ), "does-not-exist", "admin"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Transactional
    void create_acceptsPsksReadBoundToARealEdgeHub() {
        EdgeHub edge = createEdgeHub("Bind-" + uniq());
        ApiKeyService.CreateResult r = apiKeys.create("edge-key-" + uniq(),
                Set.of(ApiKeyScope.PSKS_READ), edge.id, "admin");
        assertThat(apiKeys.edgeHubIdsOf(r.apiKey().id)).containsExactly(edge.id);
    }

    @Test
    void endpoint_returnsOnlyPskBearingPeersHomedAtThisEdge() {
        EdgeHub edge = createEdgeHub("Munich-" + uniq());
        Site site = createSite("Site-" + uniq(), "10.98.0.0/24", edge.id);
        Resource res = createResource(site.id);
        User user = createUser();
        grantAllPorts(user.id, res.id);

        String homedWithPsk = persistPeer(user.id, nextIp(), "PSKVALUE1AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String homedNoPsk = persistPeer(user.id, nextIp(), null);
        // A second, ungranted user's peer has a PSK but no reachable
        // resource in this edge's site at all — must not appear either.
        User outsider = createUser();
        persistPeer(outsider.id, nextIp(), "PSKVALUE2AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

        String rawKey = apiKeys.create("psk-reader-" + uniq(), Set.of(ApiKeyScope.PSKS_READ), edge.id, "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/edge/" + edge.id + "/psks")
                .then().statusCode(200)
                .body("peers", hasSize(1))
                .body("peers[0].peerId", is(homedWithPsk));

        assertThat(homedNoPsk).isNotNull(); // sanity: the no-PSK peer exists, just not returned above
    }

    @Test
    void endpoint_refusesAKeyBoundToADifferentEdge() {
        EdgeHub home = createEdgeHub("Home-" + uniq());
        EdgeHub other = createEdgeHub("Other-" + uniq());
        String rawKey = apiKeys.create("wrong-edge-" + uniq(), Set.of(ApiKeyScope.PSKS_READ), home.id, "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/edge/" + other.id + "/psks")
                .then().statusCode(403);
    }

    @Test
    void endpoint_refusesAFullyUnrelatedScope() {
        EdgeHub edge = createEdgeHub("NoScope-" + uniq());
        String rawKey = apiKeys.create("peers-only-" + uniq(), Set.of(ApiKeyScope.PEERS_READ), "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/edge/" + edge.id + "/psks")
                .then().statusCode(403);
    }

    @Test
    void endpoint_writesOneAuditEntryPerAccess() {
        EdgeHub edge = createEdgeHub("Audit-" + uniq());
        long before = auditCount();
        String rawKey = apiKeys.create("audited-" + uniq(), Set.of(ApiKeyScope.PSKS_READ), edge.id, "admin").rawKey();

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/edge/" + edge.id + "/psks")
                .then().statusCode(200);

        assertThat(auditCount()).isEqualTo(before + 1);
    }

    @Transactional
    long auditCount() {
        return AuditLog.count("action", "edge.psks_read");
    }

    @Transactional
    void grantAllPorts(String userId, String resourceId) {
        UserResourceGrant.createNew(userId, resourceId, true).persist();
    }
}
