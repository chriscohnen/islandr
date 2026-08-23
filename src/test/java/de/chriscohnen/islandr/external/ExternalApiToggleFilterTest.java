package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.apikey.ApiKeyService;
import de.chriscohnen.islandr.settings.SettingsDto;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/** The Settings.externalApiEnabled opt-out (issue #15) — disabling it must
 *  404 the facade regardless of an otherwise-valid API key, and re-enabling
 *  it must restore normal behavior. */
@QuarkusTest
class ExternalApiToggleFilterTest {

    @Inject SettingsService settings;
    @Inject ApiKeyService apiKeys;

    @AfterEach
    @Transactional
    void restoreEnabled() {
        setExternalApiEnabled(true);
        ApiKey.deleteAll();
    }

    @Test
    void disabled_404sEvenWithAValidKey() {
        String rawKey = apiKeys.create("toggle-test", "admin").rawKey();
        setExternalApiEnabled(false);

        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/peers")
                .then().statusCode(404);
    }

    @Test
    void enabled_normalAuthBehaviorApplies() {
        setExternalApiEnabled(true);

        // No credentials at all — still a normal 401, not swallowed by the toggle.
        given().when().get("/api/external/v1/peers").then().statusCode(401);

        String rawKey = apiKeys.create("toggle-test-2", "admin").rawKey();
        given().header("Authorization", "Bearer " + rawKey)
                .when().get("/api/external/v1/peers")
                .then().statusCode(200);
    }

    @Transactional
    void setExternalApiEnabled(boolean enabled) {
        var cur = settings.get();
        settings.update(new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgSubnet6, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, cur.wgClientDns, cur.privateKeyRetention,
                cur.gravatarEnabled, cur.oidcAutoProvision, cur.firewallDryRun, cur.selfServicePeerCreation,
                cur.wgMtu, cur.wgIncludeMtuInConf, cur.wgPersistentKeepalive, cur.nominatimUrl,
                cur.hubLat, cur.hubLon, cur.hubLocationLabel,
                cur.ironRdpEnabled, cur.activityRetentionDays,
                cur.tunnelMode, cur.allowedIpsMode, cur.splitSupernet,
                cur.dnsResolverEnabled, cur.dnsResolverZone, cur.dnsResolverUpstream, enabled
        ), "test");
    }
}
