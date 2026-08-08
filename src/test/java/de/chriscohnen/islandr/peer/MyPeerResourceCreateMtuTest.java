package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.settings.SettingsDto;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Self-service device-category picker (#31 phase 2): the portal's "on the
 * go" category should apply the 1280 MTU compatibility floor automatically,
 * without the end user ever seeing the word "MTU". Stationary devices get
 * no override — the hub/global default applies.
 */
@QuarkusTest
class MyPeerResourceCreateMtuTest {

    @Inject SessionService sessions;
    @Inject SettingsService settingsSvc;

    // suggestNextIp() picks the lowest free address in the configured
    // subnet — the shared %test datasource has no per-class isolation, so a
    // peer created here and left behind can collide with another test
    // elsewhere in the suite that hardcodes an IP (e.g. PeerResourceTest's
    // "10.8.0.5"). Track and delete what this class creates.
    private final List<String> createdUserIds = new ArrayList<>();

    // Other test classes (AdminSessionExtension) pin a default admin session
    // cookie onto RestAssured's static request spec and reset it in their own
    // afterEach — but this class sends its own org-user cookie explicitly and
    // doesn't use that extension, so a stray/leftover default spec must not
    // leak in and silently win over the explicit cookie() call below.
    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    // Settings is a shared singleton row across the whole test suite (no
    // per-test DB reset). SettingsResourceTest's partial-body PUT leaves
    // selfServicePeerCreation=false behind it (Jackson defaults an omitted
    // boolean field to false) — force it back on so this class doesn't
    // depend on suite execution order.
    @BeforeEach
    @Transactional
    void ensureSelfServicePeerCreationEnabled() {
        var cur = settingsSvc.get();
        settingsSvc.update(new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgSubnet6, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, cur.wgClientDns, cur.privateKeyRetention,
                cur.gravatarEnabled, cur.oidcAutoProvision, cur.firewallDryRun, true,
                cur.wgMtu, cur.wgIncludeMtuInConf, cur.wgPersistentKeepalive, cur.nominatimUrl,
                cur.hubLat, cur.hubLon, cur.hubLocationLabel,
                cur.ironRdpEnabled, cur.activityRetentionDays,
                cur.tunnelMode, cur.allowedIpsMode, cur.splitSupernet,
                cur.dnsResolverEnabled, cur.dnsResolverZone, cur.dnsResolverUpstream
        ), "test");
    }

    @Test
    void mobileCategory_getsThe1280CompatibilityFloor() {
        String cookie = orgUserSession();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("""
                        { "name": "Phone (on the go)", "deviceType": "mobile" }
                        """)
                .when().post("/api/v1/peers/mine")
                .then().statusCode(201)
                .body("peer.mtu", equalTo(1280))
                .body("peer.deviceType", equalTo("mobile"));
    }

    @Test
    void stationaryCategory_getsNoMtuOverride() {
        String cookie = orgUserSession();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("""
                        { "name": "Office PC", "deviceType": "desktop" }
                        """)
                .when().post("/api/v1/peers/mine")
                .then().statusCode(201)
                .body("peer.mtu", nullValue())
                .body("peer.deviceType", equalTo("desktop"));
    }

    @Test
    void omittedCategory_getsNoMtuOverride() {
        String cookie = orgUserSession();
        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("""
                        { "name": "No category given" }
                        """)
                .when().post("/api/v1/peers/mine")
                .then().statusCode(201)
                .body("peer.mtu", nullValue());
    }

    @AfterEach
    @Transactional
    void cleanup() {
        for (String userId : createdUserIds) {
            Peer.delete("userId", userId);
            Session.delete("userId", userId);
            User.deleteById(userId);
        }
        createdUserIds.clear();
    }

    private String orgUserSession() {
        String userId = persistUser("Portal User", "portal-" + UUID.randomUUID() + "@firma.de");
        createdUserIds.add(userId);
        Session s = sessions.create(Session.MICROSOFT, "principal-" + userId.substring(0, 6), userId);
        return s.id;
    }

    @Transactional
    String persistUser(String name, String email) {
        User u = User.createNew(name, email);
        u.isAdmin = false;
        u.persist();
        return u.id;
    }
}
