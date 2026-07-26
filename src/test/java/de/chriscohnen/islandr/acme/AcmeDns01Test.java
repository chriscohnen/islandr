package de.chriscohnen.islandr.acme;

import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the dns-01 challenge path (ADR-0020) — both the automatic
 * Cloudflare provider and the "manual" pause/continue flow — against the
 * same scripted fake ACME server pattern {@link AcmeServiceTest} uses for
 * http-01, plus a faked Cloudflare API for the automatic case.
 */
@QuarkusTest
class AcmeDns01Test {

    private static final String DIRECTORY = "https://acme-test.example/directory";
    private static final String NEW_NONCE = "https://acme-test.example/new-nonce";
    private static final String NEW_ACCOUNT = "https://acme-test.example/new-acct";
    private static final String NEW_ORDER = "https://acme-test.example/new-order";
    private static final String ACCOUNT_URL = "https://acme-test.example/acct/1";
    private static final String ORDER_URL = "https://acme-test.example/order/1";
    private static final String AUTHZ_URL = "https://acme-test.example/authz/1";
    private static final String CHALLENGE_URL = "https://acme-test.example/challenge/1";
    private static final String FINALIZE_URL = "https://acme-test.example/finalize/1";
    private static final String CERT_URL = "https://acme-test.example/cert/1";
    private static final String DOMAIN = "vpn.example.test";
    private static final String FAKE_CERT_PEM = fixture("ec-cert.pem");

    private static String fixture(String name) {
        try (InputStream in = AcmeDns01Test.class.getResourceAsStream("/tls-fixtures/" + name)) {
            if (in == null) throw new IllegalStateException("test fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject AcmeService acme;
    @Inject FakeHttpFetcher http;
    @Inject SettingsService settingsSvc;

    @BeforeEach
    void reset() {
        http.reset();
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
        s.acmeAccountKeyPem = null;
        s.acmeAccountPubKey = null;
        s.acmeAccountUrl = null;
        s.acmeLastAttemptAt = null;
        s.acmeLastRenewalAt = null;
        s.acmeLastError = null;
        s.tlsCertPem = null;
        s.tlsKeyPem = null;
        s.acmeChallengeType = "http-01";
        s.acmeDnsProvider = null;
        s.acmeDnsApiToken = null;
        s.acmeDnsPendingRecordName = null;
        s.acmeDnsPendingRecordValue = null;
        s.acmeDnsPendingOrderUrl = null;
        s.acmeDnsPendingAuthzUrl = null;
        s.acmeDnsPendingChallengeUrl = null;
        s.acmeDnsPendingFinalizeUrl = null;
    }

    // Plain settingsSvc.get() falls back to a request-scoped Hibernate session
    // with its own long-lived first-level cache when called outside a
    // transaction. A test that reads Settings this way, then triggers more
    // @Transactional writes (each in their own separate session), then reads
    // Settings again the same way, gets back the same stale pre-write snapshot
    // on the second read — bit AcmeService itself once already (see
    // AcmeSettingsStore's class javadoc). Any test doing a mid-flow read like
    // that needs this @Transactional wrapper to force a fresh query instead.
    @Transactional
    Settings currentSettings() {
        return settingsSvc.get();
    }

    @Transactional
    void configure(String challengeType, String provider, String apiToken) {
        Settings s = settingsSvc.get();
        s.tlsMode = "acme";
        s.acmeDomain = DOMAIN;
        s.acmeChallengeType = challengeType;
        s.acmeDnsProvider = provider;
        s.acmeDnsApiToken = apiToken;
    }

    private void stubAcmeUpToAuthz(String challengeType) {
        http.stubJson(DIRECTORY, 200, """
                {"newNonce":"%s","newAccount":"%s","newOrder":"%s"}
                """.formatted(NEW_NONCE, NEW_ACCOUNT, NEW_ORDER));
        http.stub(NEW_NONCE, 200, new byte[0], Map.of("replay-nonce", "nonce-0"));

        http.postBodyStub(NEW_ACCOUNT, 201, """
                {"status":"valid"}
                """, Map.of("location", ACCOUNT_URL, "replay-nonce", "nonce-1", "content-type", "application/json"));

        http.postBodyStub(NEW_ORDER, 201, """
                {"status":"pending","authorizations":["%s"],"finalize":"%s"}
                """.formatted(AUTHZ_URL, FINALIZE_URL),
                Map.of("location", ORDER_URL, "replay-nonce", "nonce-2", "content-type", "application/json"));

        http.postBodyStub(AUTHZ_URL, 200, """
                {"status":"valid","challenges":[{"type":"%s","token":"the-token","url":"%s"}]}
                """.formatted(challengeType, CHALLENGE_URL),
                Map.of("replay-nonce", "nonce-3", "content-type", "application/json"));

        http.postBodyStub(CHALLENGE_URL, 200, """
                {"status":"pending"}
                """, Map.of("replay-nonce", "nonce-4", "content-type", "application/json"));

        http.postBodyStub(FINALIZE_URL, 200, """
                {"status":"valid","certificate":"%s"}
                """.formatted(CERT_URL),
                Map.of("replay-nonce", "nonce-5", "content-type", "application/json"));

        http.postBodyStub(ORDER_URL, 200, """
                {"status":"valid","certificate":"%s"}
                """.formatted(CERT_URL),
                Map.of("replay-nonce", "nonce-6", "content-type", "application/json"));

        http.postBodyStub(CERT_URL, 200, FAKE_CERT_PEM,
                Map.of("replay-nonce", "nonce-7", "content-type", "application/pem-certificate-chain"));
    }

    // --- Cloudflare (automatic) ----------------------------------------------

    private static final String CF_ZONES = "https://api.cloudflare.com/client/v4/zones?name=" + DOMAIN;
    private static final String CF_ZONES_PARENT = "https://api.cloudflare.com/client/v4/zones?name=example.test";
    private static final String CF_RECORDS = "https://api.cloudflare.com/client/v4/zones/zone-1/dns_records";
    private static final String CF_RECORD_DELETE = CF_RECORDS + "/record-1";

    private void stubCloudflareZoneWalkUp() {
        // No exact zone for the full name — findZoneId walks up to the parent.
        http.stubJson(CF_ZONES, 200, """
                {"result":[]}
                """);
        http.stubJson(CF_ZONES_PARENT, 200, """
                {"result":[{"id":"zone-1"}]}
                """);
        http.postBodyStub(CF_RECORDS, 200, """
                {"success":true,"result":{"id":"record-1"}}
                """, Map.of("content-type", "application/json"));
        http.deleteStub(CF_RECORD_DELETE, 200, """
                {"success":true}
                """, Map.of("content-type", "application/json"));
    }

    @Test
    void issueCertificate_dns01Cloudflare_happyPath_publishesAndCleansUpTxtRecord() {
        configure("dns-01", "cloudflare", "cf-token-abc");
        stubAcmeUpToAuthz("dns-01");
        stubCloudflareZoneWalkUp();

        acme.issueCertificate();

        Settings s = settingsSvc.get();
        assertThat(s.tlsCertPem).isEqualTo(FAKE_CERT_PEM);
        assertThat(s.acmeLastError).isNull();

        // The record was created (with the right auth header) and then removed again.
        boolean createdWithAuth = FakeHttpFetcher.calls.stream().anyMatch(c ->
                CF_RECORDS.equals(c.url()) && "Bearer cf-token-abc".equals(c.headers().get("Authorization")));
        assertThat(createdWithAuth).as("TXT record created with the configured API token").isTrue();
        boolean deleted = FakeHttpFetcher.calls.stream().anyMatch(c -> "DELETE".equals(c.method()) && CF_RECORD_DELETE.equals(c.url()));
        assertThat(deleted).as("TXT record cleaned up after issuance").isTrue();
    }

    @Test
    void issueCertificate_dns01Cloudflare_recordContentIsDigestOfKeyAuthorization() throws Exception {
        configure("dns-01", "cloudflare", "cf-token-abc");
        stubAcmeUpToAuthz("dns-01");
        stubCloudflareZoneWalkUp();

        acme.issueCertificate();

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        FakeHttpFetcher.Call createCall = FakeHttpFetcher.calls.stream()
                .filter(c -> CF_RECORDS.equals(c.url()) && c.rawBody() != null)
                .findFirst().orElseThrow();
        Map<?, ?> body = mapper.readValue(createCall.rawBodyText(), Map.class);
        assertThat(body.get("type")).isEqualTo("TXT");
        assertThat(body.get("name")).isEqualTo("_acme-challenge." + DOMAIN);
        // 43 chars — unpadded base64url of a 32-byte SHA-256 digest (RFC 8555 §8.4).
        assertThat((String) body.get("content")).hasSize(43);
    }

    @Test
    void issueCertificate_dns01_noApiToken_rejectsBeforeContactingAcme() {
        configure("dns-01", "cloudflare", null);

        assertThatThrownBy(() -> acme.issueCertificate())
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("no DNS provider API token");
    }

    @Test
    void issueCertificate_dns01_unsupportedProvider_rejects() {
        configure("dns-01", "not-a-real-provider", "token");
        stubAcmeUpToAuthz("dns-01");

        assertThatThrownBy(() -> acme.issueCertificate())
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("unsupported DNS provider");
    }

    // --- Manual (pause/continue) --------------------------------------------

    @Test
    void issueCertificate_dns01Manual_pausesAndPersistsPendingChallenge() {
        configure("dns-01", "manual", null);
        stubAcmeUpToAuthz("dns-01");

        acme.issueCertificate();

        Settings s = settingsSvc.get();
        assertThat(s.acmeDnsPendingRecordName).isEqualTo("_acme-challenge." + DOMAIN);
        assertThat(s.acmeDnsPendingRecordValue).hasSize(43);
        assertThat(s.acmeDnsPendingOrderUrl).isEqualTo(ORDER_URL);
        assertThat(s.acmeDnsPendingAuthzUrl).isEqualTo(AUTHZ_URL);
        assertThat(s.acmeDnsPendingChallengeUrl).isEqualTo(CHALLENGE_URL);
        assertThat(s.acmeDnsPendingFinalizeUrl).isEqualTo(FINALIZE_URL);
        // Not a terminal state yet — no certificate, no error.
        assertThat(s.tlsCertPem).isNull();
        assertThat(s.acmeLastError).isNull();
    }

    @Test
    void continueManualDnsChallenge_happyPath_completesIssuance() {
        configure("dns-01", "manual", null);
        stubAcmeUpToAuthz("dns-01");
        acme.issueCertificate();
        assertThat(currentSettings().acmeDnsPendingRecordValue).isNotNull();

        acme.continueManualDnsChallenge();

        Settings s = currentSettings();
        assertThat(s.tlsCertPem).isEqualTo(FAKE_CERT_PEM);
        assertThat(s.acmeLastError).isNull();
        assertThat(s.acmeDnsPendingRecordValue).as("cleared once completed").isNull();
        assertThat(s.acmeDnsPendingOrderUrl).isNull();
    }

    @Test
    void continueManualDnsChallenge_nothingPending_throws() {
        configure("dns-01", "manual", null);

        assertThatThrownBy(() -> acme.continueManualDnsChallenge())
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("no manual DNS-01 challenge");
    }
}
