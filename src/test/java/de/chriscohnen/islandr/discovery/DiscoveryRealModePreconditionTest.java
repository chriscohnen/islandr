package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Route-precondition tests that only apply to a <em>real</em> scan (ADR-0014 §3).
 * Forcing {@code islandr.discovery.mode=real} exercises the gateway check, which
 * runs before any probe is sent — so these assert on the 409/202 status without
 * ever touching a network.
 */
@QuarkusTest
@TestProfile(DiscoveryRealModePreconditionTest.RealMode.class)
@ExtendWith(AdminSessionExtension.class)
class DiscoveryRealModePreconditionTest {

    public static final class RealMode implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("islandr.discovery.mode", "real");
        }
    }

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("id");
    }

    /** Attach a site gateway peer whose last handshake is older than the connect window. */
    private void attachStaleGateway(String siteId, String publicKey, String gwIp) {
        QuarkusTransaction.requiringNew().run(() -> {
            Peer gw = Peer.createNew(null, "stale-gw", publicKey, gwIp);
            gw.type = "site";
            gw.lastSeenAt = Instant.now().minus(30, ChronoUnit.MINUTES);
            gw.persist();
            Site s = Site.findById(siteId);
            s.gatewayPeerId = gw.id;
        });
    }

    @Test
    void realScan_declaredGatewayButStaleHandshake_returns409() {
        String siteId = createSite("disco-stale", "10.94.0.0/29");
        attachStaleGateway(siteId, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", "10.94.0.2");

        given().contentType("application/json")
                .when().post("/api/v1/sites/" + siteId + "/discovery/scan")
                .then().statusCode(409);
    }

    /** force=true is the deliberate escape hatch for an admin pre-configuring a site
     *  while the enforcement plane is degraded (e.g. the Docker socket proxy isn't
     *  wired up yet, ahead of a planned native-instance rollout) — the handshake
     *  timestamp is meaningless in that state, and they want to check reachability
     *  directly rather than be blocked by it. */
    @Test
    void realScan_forceTrue_bypassesTheStaleHandshakeCheck() {
        String siteId = createSite("disco-stale-forced", "10.94.1.0/29");
        attachStaleGateway(siteId, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=", "10.94.1.2");

        given().contentType("application/json")
                .when().post("/api/v1/sites/" + siteId + "/discovery/scan?force=true")
                .then().statusCode(202)
                .body("jobId", org.hamcrest.Matchers.notNullValue());
    }
}
