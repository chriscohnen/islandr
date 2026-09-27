package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsDto;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;

/**
 * Retention mode {@code encrypted} on an instance that has no encryption key
 * loaded (BR-022, BR-038).
 *
 * <p>Found on a dev instance: settings said {@code encrypted}, no key was
 * loaded, and every "new device" in the portal failed with a bare HTTP 500 —
 * no body, nothing to tell anyone what was wrong. Two rules close it: the
 * settings refuse the mode while no key is loaded, and an instance that is
 * already in that state answers 503 with a sentence instead of an empty 500.
 */
@QuarkusTest
@TestProfile(EncryptedRetentionWithoutKeyTest.NoKey.class)
class EncryptedRetentionWithoutKeyTest {

    /** The %test profile loads a fixed key; this class needs an instance without one. */
    public static final class NoKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("islandr.encryption.key", "");
        }
    }

    @Inject SettingsService settingsSvc;
    @Inject SessionService sessions;
    @Inject EncryptionService encSvc;

    private String userId;

    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        Settings s = settingsSvc.get();
        s.privateKeyRetention = "never";
        if (userId != null) {
            Peer.delete("userId", userId);
            Session.delete("userId", userId);
            User.deleteById(userId);
            userId = null;
        }
    }

    @Test
    void precondition_noKeyIsLoaded() {
        assertThat(encSvc.isConfigured()).isFalse();
    }

    // BR-038: the settings refuse "encrypted" while no key is loaded — from
    // "never" as well, where no stored key would have to be migrated.
    @Test
    void settings_refuseEncryptedRetentionWithoutKey() {
        setRetentionDirectly("never");
        assertThatThrownBy(() -> settingsSvc.update(withRetention("encrypted"), "test"))
                .isInstanceOf(WebApplicationException.class)
                .satisfies(e -> {
                    var r = ((WebApplicationException) e).getResponse();
                    assertThat(r.getStatus()).isEqualTo(400);
                    assertThat(String.valueOf(r.getEntity())).contains("encryption key");
                });
        assertThat(settingsSvc.get().privateKeyRetention).isEqualTo("never");
    }

    // BR-022: an instance already in that state (set before BR-038 existed, or
    // the key failed to load after a restart) tells the caller what is wrong.
    @Test
    void selfServiceCreate_withoutKey_answers503WithAReason() {
        setRetentionDirectly("encrypted");
        String cookie = orgUserSession();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("""
                        { "name": "Laptop", "deviceType": "desktop" }
                        """)
                .when().post("/api/v1/peers/mine")
                .then().statusCode(503)
                .body(containsString("encryption key"));
        assertThat(Peer.count("userId", userId)).isZero();
    }

    @Transactional
    void setRetentionDirectly(String mode) {
        Settings s = settingsSvc.get();
        s.privateKeyRetention = mode;
        s.selfServicePeerCreation = true;
    }

    private SettingsDto.UpdateRequest withRetention(String mode) {
        var cur = settingsSvc.get();
        return new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgSubnet6, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, cur.wgClientDns, mode,
                cur.gravatarEnabled, cur.oidcAutoProvision, cur.firewallDryRun, cur.selfServicePeerCreation,
                cur.wgMtu, cur.wgIncludeMtuInConf, cur.wgPersistentKeepalive, cur.nominatimUrl,
                cur.hubLat, cur.hubLon, cur.hubLocationLabel,
                cur.ironRdpEnabled, cur.activityRetentionDays,
                cur.tunnelMode, cur.allowedIpsMode, cur.splitSupernet,
                cur.dnsResolverEnabled, cur.dnsResolverZone, cur.dnsHubAlias, cur.dnsResolverUpstream, cur.externalApiEnabled,
                cur.trustedProxies, cur.clientIpHeader);
    }

    private String orgUserSession() {
        userId = persistUser("Portal User", "portal-" + UUID.randomUUID() + "@firma.de");
        return sessions.create(Session.MICROSOFT, "principal-" + userId.substring(0, 6), userId).id;
    }

    @Transactional
    String persistUser(String name, String email) {
        User u = User.createNew(name, email);
        u.isAdmin = false;
        u.persist();
        return u.id;
    }
}
