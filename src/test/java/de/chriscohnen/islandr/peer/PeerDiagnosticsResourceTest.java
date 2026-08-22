package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.network.MockNetworkDiagnosticsAdapter;
import de.chriscohnen.islandr.network.NetworkDiagnosticsAdapter;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST surface for pinging a site's gateway peer directly (ADR-0025) — distinct from
 * {@code ResourceResource}'s ping: this tests the tunnel itself (the peer's own WireGuard
 * IP), not a resource behind it. Against {@link MockNetworkDiagnosticsAdapter} (test
 * profile pins {@code islandr.diag.mode=mock}).
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerDiagnosticsResourceTest {

    @Inject NetworkDiagnosticsAdapter diagnostics;

    private MockNetworkDiagnosticsAdapter mock() {
        return (MockNetworkDiagnosticsAdapter) ClientProxy.unwrap(diagnostics);
    }

    private String createSitePeer(String suffix) {
        String[] id = new String[1];
        QuarkusTransaction.requiringNew().run(() -> {
            String key = ("PEERDIAGKEY" + suffix).repeat(4);
            key = key.substring(0, 43) + "=";
            Peer p = Peer.createNew(null, "peerdiag-gateway-" + suffix, key, "10.95.0." + (1 + Math.floorMod(suffix.hashCode(), 250)));
            p.persist();
            id[0] = p.id;
        });
        return id[0];
    }

    @Test
    void ping_unknownPeer_is404() {
        given().contentType("application/json")
                .when().post("/api/v1/peers/" + UUID.randomUUID() + "/diagnostics/ping")
                .then().statusCode(404);
    }

    @Test
    void ping_sitePeer_reportsLatencyAndHubToPeerPath() {
        mock().forceUnreachable = false;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String peerId = createSitePeer(suffix);

        given().contentType("application/json")
                .when().post("/api/v1/peers/" + peerId + "/diagnostics/ping")
                .then().statusCode(200)
                .body("reachable", equalTo(true))
                .body("targetId", equalTo(peerId))
                .body("path", hasSize(2))
                .body("path[0].kind", equalTo("hub"))
                .body("path[1].kind", equalTo("peer"))
                .body("path[1].id", equalTo(peerId));
    }

    @Test
    void tracepath_sitePeer_reportsHops() {
        mock().forceUnreachable = false;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String peerId = createSitePeer(suffix);

        given().contentType("application/json")
                .when().post("/api/v1/peers/" + peerId + "/diagnostics/tracepath")
                .then().statusCode(200)
                .body("hops", hasSize(2));
    }
}
