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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code GET /api/v1/peers/mine/{id}/conf} under {@code encrypted} retention
 * — the exact path My access's own "QR/.conf" re-download button calls
 * (users-self-delete-peer's neighbour task). Nothing previously exercised
 * this self-service endpoint under any retention mode at all: the admin
 * side has {@link PeerResourceEncryptedRetentionTest}, but the frontend's
 * own {@code canReshow()} gate — fixed here from checking
 * {@code retention === 'plaintext'} to {@code retention !== 'never'} — had
 * no backend test proving the self-service path actually works for the
 * mode it now shows the button for.
 */
@QuarkusTest
class MyPeerResourceReshowEncryptedRetentionTest {

    @Inject SessionService sessions;
    @Inject SettingsService settingsSvc;

    private final List<String> createdUserIds = new ArrayList<>();

    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    @BeforeEach
    @Transactional
    void switchToEncryptedRetention() {
        setRetention("encrypted");
    }

    @AfterEach
    @Transactional
    void resetRetention() {
        setRetention("never");
    }

    @Transactional
    void setRetention(String mode) {
        var cur = settingsSvc.get();
        settingsSvc.update(new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgSubnet6, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, cur.wgClientDns, mode,
                cur.gravatarEnabled, cur.oidcAutoProvision, cur.firewallDryRun, true,
                cur.wgMtu, cur.wgIncludeMtuInConf, cur.wgPersistentKeepalive, cur.nominatimUrl,
                cur.hubLat, cur.hubLon, cur.hubLocationLabel,
                cur.ironRdpEnabled, cur.activityRetentionDays,
                cur.tunnelMode, cur.allowedIpsMode, cur.splitSupernet,
                cur.dnsResolverEnabled, cur.dnsResolverZone, cur.dnsHubAlias, cur.dnsResolverUpstream, cur.externalApiEnabled,
                cur.trustedProxies, cur.clientIpHeader
        ), "test");
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

    @Test
    void selfServiceReshow_returnsPlaintextKeyAndConf_underEncryptedRetention() {
        String cookie = orgUserSession();

        String peerId = given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .contentType("application/json")
                .body("{ \"name\": \"phone\" }")
                .when().post("/api/v1/peers/mine")
                .then().statusCode(201)
                // The create response's own privateKey must already be the raw
                // key, not the "enc$..." stored form — same contract as the
                // admin-side create endpoint.
                .body("privateKey", notNullValue())
                .body("privateKey", not(startsWith("enc$")))
                .extract().path("peer.id");

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine/" + peerId + "/conf")
                .then().statusCode(200)
                .body("privateKey", notNullValue())
                .body("privateKey", not(startsWith("enc$")))
                .body("conf", notNullValue())
                .body("qrPngBase64", notNullValue());
    }

    /** No stored private key (retention "never", or an imported public key):
     *  the user still gets the .conf with everything the server knows —
     *  address, DNS, endpoint, allowed IPs — just without the PrivateKey line
     *  and without a QR code, which would be useless without it. Same as the
     *  admin-side reshow. */
    @Test
    void selfServiceReshow_servesConfWithoutKey_whenNoPrivateKeyWasStored() {
        String cookie = orgUserSession();
        String userId = currentUserId;
        String peerId = persistPeerWithoutKey(userId, "imported-device");

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine/" + peerId + "/conf")
                .then().statusCode(200)
                .body("peer.id", org.hamcrest.Matchers.equalTo(peerId))
                .body("privateKey", org.hamcrest.Matchers.nullValue())
                .body("qrPngBase64", org.hamcrest.Matchers.nullValue())
                .body("conf", org.hamcrest.Matchers.containsString("[Peer]"))
                .body("conf", not(org.hamcrest.Matchers.containsString("PrivateKey")));
    }

    private String currentUserId;

    private String orgUserSession() {
        String userId = persistUser("Portal User", "portal-" + UUID.randomUUID() + "@firma.de");
        createdUserIds.add(userId);
        currentUserId = userId;
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

    @Transactional
    String persistPeerWithoutKey(String userId, String name) {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(userId, name, publicKey,
                "10.9.0." + (200 + (int) (Math.random() * 50)));
        p.privateKeyPem = null;
        p.persist();
        return p.id;
    }
}
