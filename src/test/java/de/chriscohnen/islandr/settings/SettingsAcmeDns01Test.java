package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * REST-level validation for the dns-01 fields on {@code PUT /api/v1/settings/acme}
 * (ADR-0020) and the new {@code POST /api/v1/settings/acme/dns-continue} endpoint.
 * The actual ACME protocol exchange is covered by {@code AcmeDns01Test}; this is
 * about what {@link SettingsService#enableAcme} rejects before ever reaching it.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class SettingsAcmeDns01Test {

    @Inject SettingsService settingsSvc;

    @BeforeEach
    void reset() {
        resetSettings();
    }

    @AfterEach
    void cleanup() {
        resetSettings();
    }

    @Transactional
    void resetSettings() {
        Settings s = settingsSvc.get();
        s.tlsMode = "none";
        s.acmeDomain = null;
        s.acmeChallengeType = "http-01";
        s.acmeDnsProvider = null;
        s.acmeDnsApiToken = null;
        s.acmeDnsPendingRecordName = null;
        s.acmeDnsPendingRecordValue = null;
        s.acmeDnsPendingOrderUrl = null;
        s.acmeDnsPendingAuthzUrl = null;
        s.acmeDnsPendingChallengeUrl = null;
        s.acmeDnsPendingFinalizeUrl = null;
        s.acmeLastAttemptAt = null;
        s.acmeLastError = null;
        s.acmeAccountUrl = null;
        s.acmeAccountKeyPem = null;
        s.acmeAccountPubKey = null;
    }

    @Test
    void enableAcme_invalidChallengeType_rejected() {
        given().contentType("application/json")
                .body(Map.of("domain", "vpn.example.test", "challengeType", "not-a-real-type"))
                .when().put("/api/v1/settings/acme")
                .then().statusCode(400);
    }

    @Test
    void enableAcme_invalidDnsProvider_rejected() {
        given().contentType("application/json")
                .body(Map.of("domain", "vpn.example.test", "challengeType", "dns-01", "dnsProvider", "not-a-real-provider"))
                .when().put("/api/v1/settings/acme")
                .then().statusCode(400);
    }

    @Test
    void enableAcme_dns01Cloudflare_noToken_rejected() {
        given().contentType("application/json")
                .body(Map.of("domain", "vpn.example.test", "challengeType", "dns-01", "dnsProvider", "cloudflare"))
                .when().put("/api/v1/settings/acme")
                .then().statusCode(400);
    }

    @Test
    void enableAcme_dns01Manual_noTokenNeeded_acceptedAtTheValidationLevel() {
        // Issuance itself will fail (fake ACME server unreachable in this profile),
        // but that's a 200-with-acmeLastError outcome, not a validation 400 — the
        // point here is that "manual" alone, with no token, clears validation.
        given().contentType("application/json")
                .body(Map.of("domain", "vpn.example.test", "challengeType", "dns-01", "dnsProvider", "manual"))
                .when().put("/api/v1/settings/acme")
                .then().statusCode(200)
                .body("acmeChallengeType", equalTo("dns-01"))
                .body("acmeDnsProvider", equalTo("manual"));
    }

    @Test
    void enableAcme_omittedChallengeFields_keepsExistingConfig() {
        given().contentType("application/json")
                .body(Map.of("domain", "vpn.example.test", "challengeType", "dns-01", "dnsProvider", "manual"))
                .when().put("/api/v1/settings/acme")
                .then().statusCode(200);

        // Re-enabling with just a (new) domain and no challenge fields must not
        // reset back to http-01/null — it should keep what's already on file.
        given().contentType("application/json")
                .body(Map.of("domain", "vpn2.example.test"))
                .when().put("/api/v1/settings/acme")
                .then().statusCode(200)
                .body("acmeDomain", equalTo("vpn2.example.test"))
                .body("acmeChallengeType", equalTo("dns-01"))
                .body("acmeDnsProvider", equalTo("manual"));
    }

    @Test
    void continueAcmeDns_nothingPending_returns200WithError() {
        given().when().post("/api/v1/settings/acme/dns-continue")
                .then().statusCode(200)
                .body("acmeLastError", notNullValue());
    }
}
