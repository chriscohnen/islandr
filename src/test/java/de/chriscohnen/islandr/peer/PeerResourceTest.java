package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.SettingsDto;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests run against the default {@code retention=never} profile (set in
 * {@code application.properties} %test profile is inherited).
 * The {@code plaintext} branch is exercised in {@link PeerResourcePlaintextRetentionTest}.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerResourceTest {

    @Inject SettingsService settings;

    /** Mutates the shared settings singleton — callers must restore it (see the
     *  includeDns tests' try/finally) since the row is shared across the suite. */
    @Transactional
    void setGlobalDns(String dns) {
        var cur = settings.get();
        settings.update(new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgSubnet6, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, dns, cur.privateKeyRetention,
                cur.gravatarEnabled, cur.oidcAutoProvision, cur.firewallDryRun, cur.selfServicePeerCreation,
                cur.wgMtu, cur.wgIncludeMtuInConf, cur.wgPersistentKeepalive, cur.nominatimUrl,
                cur.hubLat, cur.hubLon, cur.hubLocationLabel,
                cur.ironRdpEnabled, cur.activityRetentionDays,
                cur.tunnelMode, cur.allowedIpsMode, cur.splitSupernet,
                cur.dnsResolverEnabled, cur.dnsResolverZone, cur.dnsResolverUpstream
        ), "test");
    }

    /** Mutates the shared settings singleton — callers must restore it (see the
     *  resolver tests' try/finally) since the row is shared across the suite.
     *  Goes through {@code SettingsService} directly (not the HTTP endpoint),
     *  so it never triggers {@code DnsResolverService.reconcile()} — no socket
     *  bind attempt from this test. */
    @Transactional
    void setDnsResolverEnabled(boolean enabled) {
        var cur = settings.get();
        settings.update(new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgSubnet6, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, cur.wgClientDns, cur.privateKeyRetention,
                cur.gravatarEnabled, cur.oidcAutoProvision, cur.firewallDryRun, cur.selfServicePeerCreation,
                cur.wgMtu, cur.wgIncludeMtuInConf, cur.wgPersistentKeepalive, cur.nominatimUrl,
                cur.hubLat, cur.hubLon, cur.hubLocationLabel,
                cur.ironRdpEnabled, cur.activityRetentionDays,
                cur.tunnelMode, cur.allowedIpsMode, cur.splitSupernet,
                enabled, cur.dnsResolverZone, cur.dnsResolverUpstream
        ), "test");
    }

    private String createUser() {
        return given().contentType("application/json")
                .body("""
                        { "name": "Felix", "email": "felix-%s@example.com" }
                        """.formatted(java.util.UUID.randomUUID()))
                .when().post("/api/v1/users")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void create_returnsConfPrivateKeyQrAndPeer() {
        String userId = createUser();

        var resp = given().contentType("application/json")
                .body("""
                        { "name": "macbook", "assignedIp": "10.8.0.5" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.id", notNullValue())
                .body("peer.assignedIp", equalTo("10.8.0.5"))
                .body("peer.publicKey", notNullValue())
                .body("privateKey", notNullValue())
                .body("conf", containsString("[Interface]"))
                .body("conf", containsString("[Peer]"))
                .body("conf", containsString("PrivateKey ="))
                .body("qrPngBase64", startsWith("data:image/png;base64,"))
                .extract().response();

        // private key in the response must NOT equal the public key — sanity guard against
        // accidentally mirroring the same value into both fields
        String priv = resp.path("privateKey");
        String pub = resp.path("peer.publicKey");
        org.junit.jupiter.api.Assertions.assertNotEquals(priv, pub);
    }

    @Test
    void create_rejectsIpOutsideSubnet() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        { "name": "bad", "assignedIp": "192.168.1.50" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_rejectsMalformedIp() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        { "name": "bad", "assignedIp": "10.8.0.notanumber" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_rejectsDuplicateIp() {
        String userId = createUser();
        String body = """
                { "name": "first", "assignedIp": "10.8.0.7" }
                """;
        given().contentType("application/json").body(body)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201);

        given().contentType("application/json")
                .body("""
                        { "name": "second", "assignedIp": "10.8.0.7" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(409);
    }

    @Test
    void create_rejectsUnknownUser() {
        given().contentType("application/json")
                .body("""
                        { "name": "x", "assignedIp": "10.8.0.8" }
                        """)
                .when().post("/api/v1/users/does-not-exist/peers")
                .then().statusCode(404);
    }

    @Test
    void getConf_returnsKeylessConfInNeverRetentionMode() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "x", "assignedIp": "10.8.0.9" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        // In never-retention mode the reshow endpoint still serves a .conf so the
        // user can recover the server-side parameters — just without PrivateKey
        // and without a (useless) QR. The user must paste the key in manually or
        // create a fresh peer.
        given().when().get("/api/v1/peers/" + peerId + "/conf")
                .then().statusCode(200)
                .body("peer.id", equalTo(peerId))
                .body("privateKey", nullValue())
                .body("qrPngBase64", nullValue())
                .body("conf", containsString("[Interface]"))
                .body("conf", containsString("[Peer]"))
                .body("conf", not(containsString("PrivateKey")));
    }

    @Test
    void getConf_returns404ForUnknownPeer() {
        given().when().get("/api/v1/peers/does-not-exist/conf")
                .then().statusCode(404);
    }

    // ---- Key import paths (PRD F-03 extension) -----------------------------
    //
    // A fixed 44-char Base64 string serves as a sample WireGuard key. The mock
    // adapter accepts any 44-char Base64 as a "key" and derives a public key
    // deterministically via SHA-256, so we can drive the three import branches
    // through the public REST surface without touching adapter internals.

    // Exactly 44 chars: 43 [A-Za-z0-9+/] + '='. Matches the validator regex on
    // PeerDto.CreateRequest. Cryptographically meaningless — the mock adapter
    // accepts any string of this shape.
    private static final String SAMPLE_PRIVATE_KEY =
            "AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555ABc=";
    // SHA-256 of SAMPLE_PRIVATE_KEY (UTF-8 bytes), base64-encoded — what
    // MockWgAdapter.derivePublicKey produces. Derived at test runtime so the
    // pairing test catches accidental changes to the mock derivation.
    private static final String SAMPLE_PUBLIC_KEY_FROM_PRIVATE =
            deriveForTest(SAMPLE_PRIVATE_KEY);

    private static String deriveForTest(String priv) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.Base64.getEncoder().encodeToString(
                    md.digest(priv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void create_publicKeyOnlyImport_returnsKeylessConfAndNoQr() {
        String userId = createUser();
        String unrelatedPubKey = "QQQQ1111RRRR2222SSSS3333TTTT4444UUUU5555ABc=";

        given().contentType("application/json")
                .body("""
                        {
                          "name": "client-generated",
                          "assignedIp": "10.8.0.30",
                          "publicKey": "%s"
                        }
                        """.formatted(unrelatedPubKey))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.publicKey", equalTo(unrelatedPubKey))
                .body("privateKey", nullValue())
                .body("qrPngBase64", nullValue())
                .body("conf", containsString("[Interface]"))
                .body("conf", not(containsString("PrivateKey")));
    }

    @Test
    void create_publicAndPrivateImport_acceptsAndReturnsConf() {
        String userId = createUser();

        given().contentType("application/json")
                .body("""
                        {
                          "name": "pivpn-migrated",
                          "assignedIp": "10.8.0.31",
                          "publicKey": "%s",
                          "privateKey": "%s"
                        }
                        """.formatted(SAMPLE_PUBLIC_KEY_FROM_PRIVATE, SAMPLE_PRIVATE_KEY))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.publicKey", equalTo(SAMPLE_PUBLIC_KEY_FROM_PRIVATE))
                .body("privateKey", equalTo(SAMPLE_PRIVATE_KEY))
                .body("conf", containsString("PrivateKey = " + SAMPLE_PRIVATE_KEY))
                .body("qrPngBase64", startsWith("data:image/png;base64,"));
    }

    @Test
    void create_publicAndPrivateImport_rejectsMismatchedPair() {
        String userId = createUser();
        // Public key that does NOT pair with SAMPLE_PRIVATE_KEY — the mock derives
        // SHA-256, so any unrelated 44-char string will trip the pairing check.
        String wrongPub = "ZZZZ9999YYYY8888XXXX7777WWWW6666VVVV5555ABc=";

        given().contentType("application/json")
                .body("""
                        {
                          "name": "mismatched",
                          "assignedIp": "10.8.0.32",
                          "publicKey": "%s",
                          "privateKey": "%s"
                        }
                        """.formatted(wrongPub, SAMPLE_PRIVATE_KEY))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_privateOnlyWithoutPublic_rejected() {
        String userId = createUser();

        given().contentType("application/json")
                .body("""
                        {
                          "name": "broken",
                          "assignedIp": "10.8.0.33",
                          "privateKey": "%s"
                        }
                        """.formatted(SAMPLE_PRIVATE_KEY))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    // ---- Next-IP suggestion ------------------------------------------------
    //
    // The DB is shared across this @QuarkusTest class — earlier tests may have
    // left peers behind. So these tests don't assert specific octets, they
    // assert the *contract*: the suggestion is in the configured subnet, it
    // changes after a peer takes the suggested IP, and it doesn't suggest an
    // IP that's already in use.

    @Test
    void nextIp_returnsAddressInsideSubnet() {
        String ip = nextIp();
        assertThat(ip).isNotNull();
        // Default test subnet is 10.8.0.0/24.
        assertThat(ip).matches("^10\\.8\\.0\\.\\d{1,3}$");
        assertThat(ip).isNotEqualTo("10.8.0.0").isNotEqualTo("10.8.0.1").isNotEqualTo("10.8.0.255");
    }

    @Test
    void nextIp_changesAfterSuggestedIpIsTaken() {
        String userId = createUser();
        String firstSuggestion = nextIp();

        // Claim the suggested IP. Unique name so we don't trip a collision
        // with leftover state from a sibling test.
        given().contentType("application/json")
                .body("""
                        { "name": "next-ip-claim-%s", "assignedIp": "%s" }
                        """.formatted(java.util.UUID.randomUUID().toString().substring(0, 8), firstSuggestion))
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201);

        assertThat(nextIp()).isNotEqualTo(firstSuggestion);
    }

    @Test
    void nextIp_neverSuggestsAlreadyTakenIp() {
        java.util.List<String> takenIps = given().when().get("/api/v1/peers")
                .then().statusCode(200)
                .extract().jsonPath().getList("assignedIp", String.class);

        assertThat(takenIps).doesNotContain(nextIp());
    }

    private static String nextIp() {
        return given().when().get("/api/v1/peers/next-ip")
                .then().statusCode(200)
                .extract().jsonPath().getString("assignedIp");
    }

    // ---- Site peers --------------------------------------------------------
    //
    // The DB is shared with the rest of the suite, so site CIDR overlap checks
    // here must not pick networks that another test could collide on. We use a
    // 192.168.0/24-ish range that no other test touches.

    @Test
    void create_sitePeer_storesTypeAndCidrs() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        {
                          "name": "branch-office",
                          "assignedIp": "10.8.0.40",
                          "type": "site",
                          "siteAllowedCidrs": "192.168.60.0/24"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.type", equalTo("site"))
                .body("peer.siteAllowedCidrs", equalTo("192.168.60.0/24"));
    }

    @Test
    void create_sitePeer_acceptsMultipleCidrsAndNormalises() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        {
                          "name": "branch-multi",
                          "assignedIp": "10.8.0.41",
                          "type": "site",
                          "siteAllowedCidrs": " 192.168.61.0/24 ,192.168.62.0/24 "
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.siteAllowedCidrs", equalTo("192.168.61.0/24, 192.168.62.0/24"));
    }

    @Test
    void create_sitePeer_rejectsCidrOverlappingWgSubnet() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        {
                          "name": "self-overlap",
                          "assignedIp": "10.8.0.42",
                          "type": "site",
                          "siteAllowedCidrs": "10.8.0.0/16"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_sitePeer_rejectsOverlapWithExistingSitePeer() {
        String userId = createUser();
        // First site peer claims 192.168.70.0/24.
        given().contentType("application/json")
                .body("""
                        {
                          "name": "office-a",
                          "assignedIp": "10.8.0.43",
                          "type": "site",
                          "siteAllowedCidrs": "192.168.70.0/24"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201);

        // Second peer tries to declare an overlapping /16 — must be rejected.
        given().contentType("application/json")
                .body("""
                        {
                          "name": "office-b",
                          "assignedIp": "10.8.0.44",
                          "type": "site",
                          "siteAllowedCidrs": "192.168.0.0/16"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_sitePeer_rejectsEmptyCidrList() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        {
                          "name": "no-cidrs",
                          "assignedIp": "10.8.0.45",
                          "type": "site"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_clientPeer_rejectsSiteCidrs() {
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        {
                          "name": "client-with-cidrs",
                          "assignedIp": "10.8.0.46",
                          "type": "client",
                          "siteAllowedCidrs": "192.168.99.0/24"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void create_defaultTypeIsClient() {
        String userId = createUser();
        // No "type" field at all → server defaults to "client".
        given().contentType("application/json")
                .body("""
                        {
                          "name": "default-typed",
                          "assignedIp": "10.8.0.47"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.type", equalTo("client"))
                .body("peer.siteAllowedCidrs", nullValue());
    }

    @Test
    void create_malformedKey_rejectedByValidation() {
        String userId = createUser();

        given().contentType("application/json")
                .body("""
                        {
                          "name": "bad-key",
                          "assignedIp": "10.8.0.34",
                          "publicKey": "not-base64"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(400);
    }

    @Test
    void deleteAndDisable_roundtrip() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "x", "assignedIp": "10.8.0.10" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        // disable
        given().contentType("application/json")
                .body("""
                        { "enabled": false }
                        """)
                .when().put("/api/v1/peers/" + peerId + "/enabled")
                .then().statusCode(200)
                .body("enabled", equalTo(false));

        // delete
        given().when().delete("/api/v1/peers/" + peerId)
                .then().statusCode(204);

        given().when().get("/api/v1/peers/" + peerId)
                .then().statusCode(404);
    }

    @Test
    void setEnabled_withReason_isAccepted() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "reasoned", "assignedIp": "10.8.0.11" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "enabled": false, "reason": "leave cover" }
                        """)
                .when().put("/api/v1/peers/" + peerId + "/enabled")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("enabledSource", equalTo("manual"));
    }

    // ---- Schedule (PUT/GET/DELETE /peers/{id}/schedule, #47) ---------------

    @Test
    void schedule_getReturns404WhenNoneSet() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "sched-none", "assignedIp": "10.8.0.12" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().when().get("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(404);
    }

    @Test
    void schedule_createThenGetThenDelete_roundtrips() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "sched-crud", "assignedIp": "10.8.0.13" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "weekdayMask": 31, "activeFrom": "08:00", "activeTo": "18:00" }
                        """)
                .when().put("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(200)
                .body("weekdayMask", equalTo(31))
                .body("activeFrom", notNullValue())
                .body("activeTo", notNullValue());

        given().when().get("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(200)
                .body("weekdayMask", equalTo(31));

        given().when().delete("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(204);

        given().when().get("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(404);
    }

    @Test
    void schedule_deleteLeavesEnabledUntouched() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "sched-delete-noop", "assignedIp": "10.8.0.14" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "weekdayMask": 1, "activeFrom": "08:00", "activeTo": "18:00" }
                        """)
                .when().put("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(200);

        given().when().delete("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(204);

        given().when().get("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("enabled", equalTo(true));
    }

    @Test
    void schedule_rejectsZeroWeekdayMask() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "sched-bad-mask", "assignedIp": "10.8.0.15" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "weekdayMask": 0, "activeFrom": "08:00", "activeTo": "18:00" }
                        """)
                .when().put("/api/v1/peers/" + peerId + "/schedule")
                .then().statusCode(400);
    }

    // ---- Update (PUT /peers/{id}) ------------------------------------------
    //
    // IPs in the .50–.59 range are reserved for update tests so they don't
    // collide with the create-suite's .30–.49. Site CIDRs use 192.168.80.x.

    @Test
    void update_changesName() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "old-name", "assignedIp": "10.8.0.50" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "renamed", "assignedIp": "10.8.0.50" }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.name", equalTo("renamed"))
                .body("peer.assignedIp", equalTo("10.8.0.50"));
    }

    @Test
    void update_changesAssignedIp() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "mover", "assignedIp": "10.8.0.51" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "mover", "assignedIp": "10.8.0.52" }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.assignedIp", equalTo("10.8.0.52"))
                .body("conf", containsString("10.8.0.52/32"));
    }

    // ---- PersistentKeepalive (issue #28) -----------------------------------
    //
    // Effective value = per-peer override (Peer.persistentKeepalive) else the
    // global default (Settings.wgPersistentKeepalive, seeded to 25). The value
    // is the switch: 0 = omit the line, N = "PersistentKeepalive = N".

    @Test
    void create_confCarriesGlobalKeepaliveDefault() {   // AC1: global 25, no override → "= 25"
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        { "name": "ka-default", "assignedIp": "10.8.0.60" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("conf", containsString("PersistentKeepalive = 25"))
                .body("peer.persistentKeepalive", nullValue());
    }

    @Test
    void update_perPeerKeepaliveOverridesGlobal() {     // AC3: override 15 → "= 15"
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "ka-override", "assignedIp": "10.8.0.61" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "ka-override", "assignedIp": "10.8.0.61", "persistentKeepalive": 15 }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.persistentKeepalive", equalTo(15))
                .body("conf", containsString("PersistentKeepalive = 15"))
                .body("conf", not(containsString("PersistentKeepalive = 25")));
    }

    @Test
    void update_perPeerKeepaliveZeroOmitsLine() {       // AC2 path: effective 0 → no line
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "ka-off", "assignedIp": "10.8.0.62" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "ka-off", "assignedIp": "10.8.0.62", "persistentKeepalive": 0 }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.persistentKeepalive", equalTo(0))
                .body("conf", not(containsString("PersistentKeepalive")));
    }

    @Test
    void update_rejectsNegativeKeepalive() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "ka-bad", "assignedIp": "10.8.0.63" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "ka-bad", "assignedIp": "10.8.0.63", "persistentKeepalive": -1 }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(400);
    }

    // ---- Per-peer DNS opt-out (issue #29) ----------------------------------
    //
    // Effective: the DNS line is written iff a global DNS is configured AND the
    // peer's includeDns flag (default true) doesn't suppress it. These tests
    // mutate the shared settings singleton, so each restores it (null) in a
    // finally block to avoid leaking into other test classes sharing the DB.

    @Test
    void create_confIncludesDnsWhenGloballyConfigured() {   // AC1: default true → DNS line present
        setGlobalDns("10.9.0.1");
        try {
            String userId = createUser();
            given().contentType("application/json")
                    .body("""
                            { "name": "dns-default", "assignedIp": "10.8.0.70" }
                            """)
                    .when().post("/api/v1/users/" + userId + "/peers")
                    .then().statusCode(201)
                    .body("conf", containsString("DNS = 10.9.0.1"))
                    .body("peer.includeDns", equalTo(true));
        } finally {
            setGlobalDns(null);
        }
    }

    @Test
    void update_perPeerIncludeDnsFalseOmitsDnsLine() {      // AC2: false → no DNS line, even though global is set
        setGlobalDns("10.9.0.1");
        try {
            String userId = createUser();
            String peerId = given().contentType("application/json")
                    .body("""
                            { "name": "dns-off", "assignedIp": "10.8.0.71" }
                            """)
                    .when().post("/api/v1/users/" + userId + "/peers")
                    .then().statusCode(201)
                    .extract().path("peer.id");

            given().contentType("application/json")
                    .body("""
                            { "name": "dns-off", "assignedIp": "10.8.0.71", "includeDns": false }
                            """)
                    .when().put("/api/v1/peers/" + peerId)
                    .then().statusCode(200)
                    .body("peer.includeDns", equalTo(false))
                    .body("conf", not(containsString("DNS =")));
        } finally {
            setGlobalDns(null);
        }
    }

    @Test
    void create_noDnsLineWhenNoGlobalDnsConfigured() {      // AC3: flag has no effect without a global DNS
        setGlobalDns(null);
        String userId = createUser();
        given().contentType("application/json")
                .body("""
                        { "name": "dns-no-global", "assignedIp": "10.8.0.72" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("conf", not(containsString("DNS =")));
    }

    @Test
    void update_omittedIncludeDnsKeepsCurrentValue() {
        // A request that omits includeDns must not silently flip it to false
        // (Boolean, not boolean, in PeerDto.UpdateRequest — see PeerService#update).
        setGlobalDns("10.9.0.1");
        try {
            String userId = createUser();
            String peerId = given().contentType("application/json")
                    .body("""
                            { "name": "dns-omit", "assignedIp": "10.8.0.73" }
                            """)
                    .when().post("/api/v1/users/" + userId + "/peers")
                    .then().statusCode(201)
                    .extract().path("peer.id");

            given().contentType("application/json")
                    .body("""
                            { "name": "dns-omit-renamed", "assignedIp": "10.8.0.73" }
                            """)
                    .when().put("/api/v1/peers/" + peerId)
                    .then().statusCode(200)
                    .body("peer.includeDns", equalTo(true))
                    .body("conf", containsString("DNS = 10.9.0.1"));
        } finally {
            setGlobalDns(null);
        }
    }

    // ---- Resource-name DNS resolver opt-in (ADR-0023) ----------------------
    //
    // When the resolver is on, the hub's own tunnel IP (network+1 of wgSubnet,
    // "10.8.0.1" for the test profile's default "10.8.0.0/24") becomes the
    // peer's primary DNS server — it's the only way a peer can reach the
    // resolver at all. Whatever's in wgClientDns is kept after it as a
    // fallback, unchanged free-text/split-DNS syntax.

    @Test
    void create_confPrefixesHubIpWhenResolverEnabled_keepingFallback() {
        setGlobalDns("10.9.0.1");
        setDnsResolverEnabled(true);
        try {
            String userId = createUser();
            given().contentType("application/json")
                    .body("""
                            { "name": "dns-resolver-on", "assignedIp": "10.8.0.74" }
                            """)
                    .when().post("/api/v1/users/" + userId + "/peers")
                    .then().statusCode(201)
                    .body("conf", containsString("DNS = 10.8.0.1, 10.9.0.1"));
        } finally {
            setDnsResolverEnabled(false);
            setGlobalDns(null);
        }
    }

    @Test
    void create_confHasHubIpOnly_whenResolverEnabledWithNoFallbackConfigured() {
        setGlobalDns(null);
        setDnsResolverEnabled(true);
        try {
            String userId = createUser();
            given().contentType("application/json")
                    .body("""
                            { "name": "dns-resolver-no-fallback", "assignedIp": "10.8.0.75" }
                            """)
                    .when().post("/api/v1/users/" + userId + "/peers")
                    .then().statusCode(201)
                    .body("conf", containsString("DNS = 10.8.0.1"))
                    .body("conf", not(containsString("DNS = 10.8.0.1,")));
        } finally {
            setDnsResolverEnabled(false);
        }
    }

    @Test
    void create_perPeerIncludeDnsFalseOmitsHubIpToo() {
        setGlobalDns("10.9.0.1");
        setDnsResolverEnabled(true);
        try {
            String userId = createUser();
            String peerId = given().contentType("application/json")
                    .body("""
                            { "name": "dns-resolver-opt-out", "assignedIp": "10.8.0.76" }
                            """)
                    .when().post("/api/v1/users/" + userId + "/peers")
                    .then().statusCode(201)
                    .extract().path("peer.id");

            given().contentType("application/json")
                    .body("""
                            { "name": "dns-resolver-opt-out", "assignedIp": "10.8.0.76", "includeDns": false }
                            """)
                    .when().put("/api/v1/peers/" + peerId)
                    .then().statusCode(200)
                    .body("conf", not(containsString("DNS =")));
        } finally {
            setDnsResolverEnabled(false);
            setGlobalDns(null);
        }
    }

    @Test
    void update_rejectsDuplicateIp() {
        String userId = createUser();
        // First peer claims .53.
        given().contentType("application/json")
                .body("""
                        { "name": "first", "assignedIp": "10.8.0.53" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201);

        // Second peer claims .54.
        String secondId = given().contentType("application/json")
                .body("""
                        { "name": "second", "assignedIp": "10.8.0.54" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        // Try to move the second one onto .53 — must conflict.
        given().contentType("application/json")
                .body("""
                        { "name": "second", "assignedIp": "10.8.0.53" }
                        """)
                .when().put("/api/v1/peers/" + secondId)
                .then().statusCode(409);
    }

    @Test
    void update_keepingSameIpDoesNotSelfConflict() {
        // Regression guard: validateAssignedIp must skip the peer's own row in
        // the duplicate-check, otherwise a no-op update of a peer's name would
        // 409 on the unchanged IP.
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "stable", "assignedIp": "10.8.0.55" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "stable-renamed", "assignedIp": "10.8.0.55" }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200);
    }

    @Test
    void update_sitePeerChangesCidrs() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        {
                          "name": "site-edit",
                          "assignedIp": "10.8.0.56",
                          "type": "site",
                          "siteAllowedCidrs": "192.168.80.0/24"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        // Self-overlap excluded: PUT can re-declare 192.168.80.0/24 alongside a new one.
        given().contentType("application/json")
                .body("""
                        {
                          "name": "site-edit",
                          "assignedIp": "10.8.0.56",
                          "siteAllowedCidrs": "192.168.80.0/24, 192.168.81.0/24"
                        }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.siteAllowedCidrs", equalTo("192.168.80.0/24, 192.168.81.0/24"));
    }

    @Test
    void update_sitePeerRejectsEmptyCidrs() {
        // Site → client via empty CIDRs is not allowed — type stays site.
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        {
                          "name": "site-stays",
                          "assignedIp": "10.8.0.57",
                          "type": "site",
                          "siteAllowedCidrs": "192.168.82.0/24"
                        }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        { "name": "site-stays", "assignedIp": "10.8.0.57" }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(400);
    }

    @Test
    void update_clientPeerRejectsCidrs() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "client-stays", "assignedIp": "10.8.0.58" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        {
                          "name": "client-stays",
                          "assignedIp": "10.8.0.58",
                          "siteAllowedCidrs": "192.168.83.0/24"
                        }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(400);
    }

    @Test
    void update_unknownPeerReturns404() {
        given().contentType("application/json")
                .body("""
                        { "name": "ghost", "assignedIp": "10.8.0.59" }
                        """)
                .when().put("/api/v1/peers/does-not-exist")
                .then().statusCode(404);
    }

    // ---- Admin key rotation (issue #46) ------------------------------------
    //
    // POST /{id}/rotate-key regenerates both halves of the keypair server-side
    // and replaces the peer's identity on the hub — an alternative to
    // delete-and-recreate for a suspected-compromised device.

    @Test
    void rotateKey_returnsNewKeyAndMarksRotatedAt() {
        String userId = createUser();
        var created = given().contentType("application/json")
                .body("""
                        { "name": "rotate-me", "assignedIp": "10.8.0.90" }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().response();

        String peerId = created.path("peer.id");
        String originalPublicKey = created.path("peer.publicKey");
        org.junit.jupiter.api.Assertions.assertNull(created.path("peer.keyRotatedAt"));

        given().contentType("application/json").when().post("/api/v1/peers/" + peerId + "/rotate-key")
                .then().statusCode(200)
                .body("peer.id", equalTo(peerId))
                .body("peer.publicKey", not(equalTo(originalPublicKey)))
                .body("peer.keyRotatedAt", notNullValue())
                .body("privateKey", notNullValue());
    }

    @Test
    void rotateKey_preservesPresharedKey() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "rotate-with-psk", "assignedIp": "10.8.0.91", "generatePresharedKey": true }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .body("peer.hasPresharedKey", equalTo(true))
                .extract().path("peer.id");

        given().contentType("application/json").when().post("/api/v1/peers/" + peerId + "/rotate-key")
                .then().statusCode(200)
                .body("peer.hasPresharedKey", equalTo(true));
    }

    @Test
    void rotateKey_unknownPeerReturns404() {
        given().contentType("application/json").when().post("/api/v1/peers/does-not-exist/rotate-key")
                .then().statusCode(404);
    }

    // ---- Admin PSK rotation (issue #46) ------------------------------------
    //
    // PUT /{id} with presharedKeyAction="rotate" stamps pskRotatedAt independently
    // of keyRotatedAt (the two rotations are unrelated product operations).

    @Test
    void update_presharedKeyActionRotateStampsPskRotatedAtIndependently() {
        String userId = createUser();
        var created = given().contentType("application/json")
                .body("""
                        { "name": "psk-rotate-me", "assignedIp": "10.8.0.92", "generatePresharedKey": true }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().response();

        String peerId = created.path("peer.id");
        org.junit.jupiter.api.Assertions.assertNull(created.path("peer.pskRotatedAt"));
        org.junit.jupiter.api.Assertions.assertNull(created.path("peer.keyRotatedAt"));

        given().contentType("application/json")
                .body("""
                        {
                          "name": "psk-rotate-me",
                          "assignedIp": "10.8.0.92",
                          "presharedKeyAction": "rotate"
                        }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.id", equalTo(peerId))
                .body("peer.pskRotatedAt", notNullValue())
                .body("peer.keyRotatedAt", nullValue());
    }

    @Test
    void update_presharedKeyActionRemoveDoesNotStampPskRotatedAt() {
        String userId = createUser();
        String peerId = given().contentType("application/json")
                .body("""
                        { "name": "psk-remove-me", "assignedIp": "10.8.0.93", "generatePresharedKey": true }
                        """)
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201)
                .extract().path("peer.id");

        given().contentType("application/json")
                .body("""
                        {
                          "name": "psk-remove-me",
                          "assignedIp": "10.8.0.93",
                          "presharedKeyAction": "remove"
                        }
                        """)
                .when().put("/api/v1/peers/" + peerId)
                .then().statusCode(200)
                .body("peer.hasPresharedKey", equalTo(false))
                .body("peer.pskRotatedAt", nullValue());
    }
}
