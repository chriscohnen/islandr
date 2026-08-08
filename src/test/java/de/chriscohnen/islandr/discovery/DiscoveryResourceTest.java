package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Integration tests for the discovery endpoints (ADR-0014, slice 4). The scan runs
 * in the default mock mode, so the lifecycle test needs no real network.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DiscoveryResourceTest {

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("id");
    }

    /** Attach a site-type gateway peer with a fresh handshake so the scan precondition passes. */
    private void attachConnectedGateway(String siteId, String publicKey, String gwIp) {
        QuarkusTransaction.requiringNew().run(() -> {
            Peer gw = Peer.createNew(null, "dsc1-router", publicKey, gwIp);
            gw.type = "site";
            gw.lastSeenAt = Instant.now();
            gw.persist();
            Site s = Site.findById(siteId);
            s.gatewayPeerId = gw.id;
        });
    }

    @Test
    void scan_nonexistentSite_returns404() {
        given().contentType("application/json")
                .when().post("/api/v1/sites/does-not-exist/discovery/scan")
                .then().statusCode(404);
    }

    @Test
    void scan_siteWithoutGateway_isAllowedInMockMode() {
        // A hub-local site has no gateway peer; in mock mode (the test default) no
        // route is required, so the scan starts instead of 409ing (ADR-0014 §3).
        String siteId = createSite("disco-nogw", "10.90.0.0/29");
        given().contentType("application/json")
                .when().post("/api/v1/sites/" + siteId + "/discovery/scan")
                .then().statusCode(202);
    }

    @Test
    void import_createsIdempotently_andMarksAlreadyRegistered() {
        String siteId = createSite("disco-import", "10.91.0.0/29");
        String body = "{\"hosts\":[{\"ip\":\"10.91.0.5\",\"name\":\"cam-1\",\"type\":\"camera\"}]}";

        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(1)).body("skipped", equalTo(0));

        // Re-import is a no-op (idempotent on site+ip).
        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(0)).body("skipped", equalTo(1));

        given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200).body("ip", hasItem("10.91.0.5"));
    }

    @Test
    void import_adoptsDiscoveredPortsWhenProvided() {
        String siteId = createSite("disco-ports", "10.95.0.0/29");
        String body = "{\"hosts\":[{\"ip\":\"10.95.0.5\",\"name\":\"prox\",\"type\":\"rackserver\","
                + "\"ports\":[22,8006]}]}";

        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(1));

        JsonPath res = given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200).extract().jsonPath();
        assertThat(res.getList("find { it.ip == '10.95.0.5' }.ports.port", Integer.class))
                .containsExactlyInAnyOrder(22, 8006);
        assertThat(res.getList("find { it.ip == '10.95.0.5' }.ports.protocol", String.class))
                .containsExactlyInAnyOrder("SSH", "HTTPS");
    }

    @Test
    void import_setsDnsNameWhenProvided() {
        String siteId = createSite("disco-dns", "10.96.0.0/29");
        String body = "{\"hosts\":[{\"ip\":\"10.96.0.5\",\"name\":\"nas-1\",\"type\":\"nas\",\"dnsName\":\"nas-1\"}]}";

        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(1));

        given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200)
                .body("find { it.ip == '10.96.0.5' }.dnsName", equalTo("nas-1"));
    }

    @Test
    void import_dropsDnsNameOnCollision_ratherThanFailingTheWholeBatch() {
        String siteId = createSite("disco-dns-collide", "10.97.0.0/28");
        // Two hosts in the same batch requesting the same dnsName — the second
        // (and any tie) loses it silently rather than 409ing the whole import.
        String body = "{\"hosts\":[" +
                "{\"ip\":\"10.97.0.5\",\"name\":\"a\",\"type\":\"computer\",\"dnsName\":\"dup\"}," +
                "{\"ip\":\"10.97.0.6\",\"name\":\"b\",\"type\":\"computer\",\"dnsName\":\"dup\"}" +
                "]}";

        given().contentType("application/json").body(body)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200).body("imported", equalTo(2));

        JsonPath res = given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200).extract().jsonPath();
        assertThat(res.getList("dnsName", String.class))
                .as("exactly one of the two colliding rows keeps the dnsName")
                .containsExactlyInAnyOrder("dup", null);
    }

    @Test
    void import_rejectsUnknownType_returns400() {
        String siteId = createSite("disco-badtype", "10.92.0.0/29");
        given().contentType("application/json")
                .body("{\"hosts\":[{\"ip\":\"10.92.0.5\",\"name\":\"x\",\"type\":\"unknown\"}]}")
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(400);
    }

    @Test
    void scan_mockMode_findsHostsNotYetRegistered() throws InterruptedException {
        String siteId = createSite("disco-scan", "10.93.0.0/29");
        attachConnectedGateway(siteId, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "10.93.0.2");

        String jobId = given().contentType("application/json")
                .when().post("/api/v1/sites/" + siteId + "/discovery/scan")
                .then().statusCode(202).extract().path("jobId");

        JsonPath status = null;
        for (int i = 0; i < 60; i++) {
            status = given().when().get("/api/v1/sites/" + siteId + "/discovery/scan/" + jobId)
                    .then().statusCode(200).extract().jsonPath();
            if (!"running".equals(status.getString("state"))) break;
            Thread.sleep(50);
        }
        assertThat(status.getString("state")).isEqualTo("done");
        assertThat(status.getList("hosts")).isNotEmpty();
        assertThat(status.getList("hosts.alreadyRegistered", Boolean.class)).containsOnly(false);
    }
}
