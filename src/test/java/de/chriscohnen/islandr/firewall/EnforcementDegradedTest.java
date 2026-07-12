package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.proxy.EnforcementStatus;
import de.chriscohnen.islandr.wg.MockWgAdapter;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Degraded-mode behaviour when the socket proxy is unreachable (design §5,
 * BR-027/028/029). Uses the mock adapters' {@code forceUnavailable} seam to make
 * the enforcing ops throw {@code ProxyUnavailableException} exactly as the socket
 * adapters do — so the call-site handling is verified without a real proxy.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EnforcementDegradedTest {

    @Inject RulesetService rulesets;
    @Inject NftablesAdapter nftAdapter;
    @Inject WgAdapter wgAdapter;
    @Inject EnforcementStatus enforcement;

    private MockNftablesAdapter nftMock() {
        return (MockNftablesAdapter) ClientProxy.unwrap(nftAdapter);
    }

    private MockWgAdapter wgMock() {
        return (MockWgAdapter) ClientProxy.unwrap(wgAdapter);
    }

    @BeforeEach
    @Transactional
    void reset() {
        Peer.deleteAll();
        FirewallState s = FirewallState.get();
        s.lastStatus = FirewallState.NEVER;
        s.lastAttemptAt = null;
        s.lastOkAt = null;
        s.ruleCount = 0;
        s.rulesetText = null;
        s.stderrText = null;
        nftMock().resetForTests();
        wgMock().reset();
        enforcement.markActive(); // baseline: ACTIVE before each scenario
    }

    /** BR-028: proxy down during recompute → status UNAVAILABLE, FirewallState untouched, no throw. */
    @Test
    void rulesetService_proxyUnavailable_marksUnavailable_leavesFirewallStateUnchanged() {
        nftMock().forceUnavailable = true;
        try {
            rulesets.recomputeAndApply("test:degraded"); // must not throw
        } finally {
            nftMock().forceUnavailable = false;
        }

        assertThat(enforcement.state()).isEqualTo(EnforcementStatus.State.UNAVAILABLE);
        FirewallState s = readState();
        assertThat(s.lastStatus).isEqualTo(FirewallState.NEVER); // not FAILED — that's reserved for real nft rejection
    }

    /** BR-030: once enforcement applies successfully again, status returns to ACTIVE. */
    @Test
    void rulesetService_successfulApply_marksActive() {
        enforcement.markUnavailable("was down");

        rulesets.recomputeAndApply("test:ok");

        assertThat(enforcement.state()).isEqualTo(EnforcementStatus.State.ACTIVE);
    }

    /** BR-027/029: creating a peer while the proxy is down persists it (201) but never fakes enforcement. */
    @Test
    void peerCreate_proxyUnavailable_persistsPeerButMarksUnavailable() {
        wgMock().forceUnavailable = true;
        nftMock().forceUnavailable = true;
        try {
            String uid = given().contentType("application/json")
                    .body("{\"name\":\"Degraded\",\"email\":\"degraded-" + UUID.randomUUID() + "@firma.de\"}")
                    .when().post("/api/v1/users")
                    .then().statusCode(201).extract().path("id");

            given().contentType("application/json")
                    .body("{\"name\":\"laptop\",\"assignedIp\":\"10.8.0.30\"}")
                    .when().post("/api/v1/users/" + uid + "/peers")
                    .then().statusCode(201);
        } finally {
            wgMock().forceUnavailable = false;
            nftMock().forceUnavailable = false;
        }

        assertThat(enforcement.state()).isEqualTo(EnforcementStatus.State.UNAVAILABLE);
        assertThat(Peer.count()).isEqualTo(1); // peer persisted despite enforcement being down
    }

    @Transactional
    FirewallState readState() {
        FirewallState.getEntityManager().clear();
        return FirewallState.findById(FirewallState.SINGLETON_ID);
    }
}
