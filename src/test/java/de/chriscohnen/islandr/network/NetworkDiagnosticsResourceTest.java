package de.chriscohnen.islandr.network;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST surface for the admin-triggered ping/path-latency probe (ADR-0025), against
 * {@link MockNetworkDiagnosticsAdapter} (test profile pins {@code islandr.diag.mode=mock}).
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class NetworkDiagnosticsResourceTest {

    @Inject NetworkDiagnosticsAdapter diagnostics;

    private MockNetworkDiagnosticsAdapter mock() {
        return (MockNetworkDiagnosticsAdapter) ClientProxy.unwrap(diagnostics);
    }

    private String createResource(String suffix) {
        String[] ids = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Site site = Site.createNew("DiagSite-" + suffix, "10.90.0.0/24", null);
            site.persist();
            Resource r = Resource.createNew(site.id, "DiagTarget-" + suffix, "10.90.0.5", null, "computer");
            r.persist();
            ids[0] = r.id;
        });
        return ids[0];
    }

    @Test
    void availability_reportsWhatTheMockAdapterClaims() {
        given().when().get("/api/v1/diagnostics/availability")
                .then().statusCode(200)
                .body("ping", equalTo(true))
                .body("tracepath", equalTo(true))
                .body("mtr", equalTo(true));
    }

    @Test
    void ping_unknownResource_is404() {
        given().contentType("application/json").when().post("/api/v1/resources/" + UUID.randomUUID() + "/diagnostics/ping")
                .then().statusCode(404);
    }

    @Test
    void ping_reachableResource_reportsLatencyAndPath() {
        mock().forceUnreachable = false;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String resourceId = createResource(suffix);

        given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/diagnostics/ping")
                .then().statusCode(200)
                .body("reachable", equalTo(true))
                .body("sent", equalTo(4))
                .body("received", equalTo(4))
                .body("avgMs", greaterThan(0f))
                .body("path", hasSize(2)) // hub -> resource, no site gateway peer configured
                .body("path[0].kind", equalTo("hub"))
                .body("path[1].kind", equalTo("resource"));
    }

    @Test
    void ping_pathIncludesSiteGatewayPeer_whenSiteDeclaresOne() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String[] ids = new String[2];
        QuarkusTransaction.requiringNew().run(() -> {
            String key = ("DIAGGWKEY" + suffix).repeat(4);
            key = key.substring(0, 43) + "=";
            Peer gw = Peer.createNew(null, "diag-gateway-" + suffix, key, "10.91.0.1");
            gw.persist();
            Site site = Site.createNew("DiagGwSite-" + suffix, "10.91.0.0/24", null);
            site.gatewayPeerId = gw.id;
            site.persist();
            Resource r = Resource.createNew(site.id, "DiagGwTarget-" + suffix, "10.91.0.5", null, "computer");
            r.persist();
            ids[0] = r.id;
            ids[1] = gw.id;
        });

        given().contentType("application/json").when().post("/api/v1/resources/" + ids[0] + "/diagnostics/ping")
                .then().statusCode(200)
                .body("path", hasSize(3))
                .body("path[1].kind", equalTo("site-gateway"))
                .body("path[1].id", equalTo(ids[1]));
    }

    @Test
    void ping_unreachableTarget_reportsFullLossWithoutError() {
        mock().forceUnreachable = true;
        try {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String resourceId = createResource(suffix);
            given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/diagnostics/ping")
                    .then().statusCode(200)
                    .body("reachable", equalTo(false))
                    .body("lossPercent", equalTo(100.0f));
        } finally {
            mock().forceUnreachable = false;
        }
    }

    @Test
    void tracepath_reachableResource_reportsHops() {
        mock().forceUnreachable = false;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String resourceId = createResource(suffix);

        given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/diagnostics/tracepath")
                .then().statusCode(200)
                .body("hops", hasSize(2))
                .body("hops[1].host", equalTo("10.90.0.5"));
    }

    @Test
    void mtr_reachableResource_reportsHopsWithLoss() {
        mock().forceUnreachable = false;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String resourceId = createResource(suffix);

        given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/diagnostics/mtr")
                .then().statusCode(200)
                .body("hops", hasSize(2))
                .body("hops[1].host", equalTo("10.90.0.5"))
                .body("hops[1].lossPercent", equalTo(0.0f));
    }

    @Test
    void ping_secondCallWithinCooldown_isRateLimited() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String resourceId = createResource(suffix);

        given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/diagnostics/ping")
                .then().statusCode(200);
        given().contentType("application/json").when().post("/api/v1/resources/" + resourceId + "/diagnostics/ping")
                .then().statusCode(429);
    }
}
