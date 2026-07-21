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
 * Exercises {@link AcmeService#issueCertificate()} end-to-end against a
 * scripted fake ACME server (directory → newAccount → newOrder → HTTP-01 →
 * finalize → download), verifying the whole hand-rolled protocol client
 * (ADR-0019) actually produces requests a real server would accept and
 * correctly drives {@link AcmeSettingsStore}'s persistence.
 *
 * <p>The fake stubs authorization/order status as already {@code "valid"}
 * from the first read rather than modelling a realistic pending→valid
 * transition ({@link FakeHttpFetcher} returns one fixed response per URL, not
 * a sequence) — the poll loop itself is simple, low-risk control flow; the
 * risk this test is actually aimed at is the JWS signing, nonce handling,
 * and request sequencing, all of which this still fully exercises.
 */
@QuarkusTest
class AcmeServiceTest {

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
    // A real, valid self-signed EC certificate (generated once via openssl, not
    // at test time — no new runtime/build dependency). TlsKeyStoreProvider's
    // reload path actually parses this via Vert.x's real PEM loader (not
    // stubbed out), so a syntactically fake placeholder like
    // "-----BEGIN CERTIFICATE-----\nZmFrZQ==..." fails there with "No
    // certificate data found". Must specifically be an EC cert, not the RSA
    // ones ADR-0015's own tls-fixtures use — Vert.x's KeyStoreHelper checks
    // the private key's algorithm matches the certificate's public key
    // algorithm, and the cert key AcmeService actually generates is always EC
    // (Jws.generateEcKeyPair). It does not verify full cryptographic pairing
    // beyond that (TlsService.requireMatchingPair is what does that, on the
    // manual-upload path only) — an EC cert unrelated to the real cert key is
    // enough to pass this check.
    private static final String FAKE_CERT_PEM = fixture("ec-cert.pem");

    private static String fixture(String name) {
        try (InputStream in = AcmeServiceTest.class.getResourceAsStream("/tls-fixtures/" + name)) {
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

    // The settings row is a singleton shared across the whole test suite (same
    // convention/comment as SettingsTlsTest) — reset after the last test too,
    // not just before each one, so this class doesn't leave tlsMode="acme"
    // behind for whichever test class the suite runs next.
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
    }

    @Transactional
    void setAcmeDomain(String domain) {
        Settings s = settingsSvc.get();
        s.tlsMode = "acme";
        s.acmeDomain = domain;
    }

    private void stubHappyPath() {
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

        // Stubbed already-valid (see class javadoc) so both the "read challenges"
        // call and the immediately-following poll check resolve in one read.
        http.postBodyStub(AUTHZ_URL, 200, """
                {"status":"valid","challenges":[{"type":"http-01","token":"the-token","url":"%s"}]}
                """.formatted(CHALLENGE_URL),
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

    @Test
    void issueCertificate_happyPath_persistsCertAndAccountState() {
        setAcmeDomain(DOMAIN);
        stubHappyPath();

        acme.issueCertificate();

        Settings s = settingsSvc.get();
        assertThat(s.tlsCertPem).isEqualTo(FAKE_CERT_PEM);
        assertThat(s.tlsKeyPem).isNotBlank();
        assertThat(s.acmeAccountUrl).isEqualTo(ACCOUNT_URL);
        assertThat(s.acmeAccountKeyPem).isNotBlank();
        assertThat(s.acmeAccountPubKey).isNotBlank();
        assertThat(s.acmeLastRenewalAt).isNotNull();
        assertThat(s.acmeLastAttemptAt).isNotNull();
        assertThat(s.acmeLastError).isNull();
    }

    @Test
    void issueCertificate_firstRequestUsesJwkNotKid_subsequentRequestsUseKid() throws Exception {
        setAcmeDomain(DOMAIN);
        stubHappyPath();

        acme.issueCertificate();

        // newAccount is the only request that may legally carry `jwk` (RFC 8555
        // §6.2 — the account doesn't have a `kid` yet); every request after it
        // must carry `kid` instead. Decode each recorded call's protected
        // header and check which one it actually used.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var b64url = java.util.Base64.getUrlDecoder();
        int jwkCount = 0, kidCount = 0;
        for (FakeHttpFetcher.Call call : FakeHttpFetcher.calls) {
            if (call.rawBody() == null) continue; // GETs (directory, newNonce)
            Map<String, Object> jws = mapper.readValue(call.rawBodyText(), Map.class);
            Map<String, Object> header = mapper.readValue(b64url.decode((String) jws.get("protected")), Map.class);
            if (header.containsKey("jwk")) jwkCount++;
            if (header.containsKey("kid")) kidCount++;
        }
        assertThat(jwkCount).as("only newAccount should use jwk").isEqualTo(1);
        assertThat(kidCount).as("every request after newAccount should use kid")
                .isEqualTo(FakeHttpFetcher.calls.stream().filter(c -> c.rawBody() != null).count() - 1);
    }

    @Inject ChallengeHolder challenges;

    @Test
    void issueCertificate_challengeHolderIsClearedAfterCompletion() throws Exception {
        setAcmeDomain(DOMAIN);
        stubHappyPath();

        acme.issueCertificate();

        // keyAuthorizationFor is package-private but this test is in the same
        // package — no reflection needed to check the holder was cleared.
        assertThat(challenges.keyAuthorizationFor("the-token")).isNull();
    }

    @Test
    void issueCertificate_rejectedOrder_recordsErrorAndThrows() {
        setAcmeDomain(DOMAIN);
        http.stubJson(DIRECTORY, 200, """
                {"newNonce":"%s","newAccount":"%s","newOrder":"%s"}
                """.formatted(NEW_NONCE, NEW_ACCOUNT, NEW_ORDER));
        http.stub(NEW_NONCE, 200, new byte[0], Map.of("replay-nonce", "nonce-0"));
        http.postBodyStub(NEW_ACCOUNT, 201, """
                {"status":"valid"}
                """, Map.of("location", ACCOUNT_URL, "replay-nonce", "nonce-1", "content-type", "application/json"));
        http.postBodyStub(NEW_ORDER, 400, """
                {"type":"urn:ietf:params:acme:error:rejectedIdentifier","detail":"forbidden domain"}
                """, Map.of("replay-nonce", "nonce-2", "content-type", "application/json"));

        assertThatThrownBy(() -> acme.issueCertificate())
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("rejectedIdentifier");

        Settings s = settingsSvc.get();
        assertThat(s.acmeLastError).contains("rejectedIdentifier");
        assertThat(s.acmeLastRenewalAt).isNull();
        assertThat(s.tlsCertPem).isNull(); // previous/no certificate untouched
    }

    @Test
    void issueCertificate_reusesExistingAccountKeyOnASecondRun() {
        setAcmeDomain(DOMAIN);
        stubHappyPath();
        acme.issueCertificate();
        String firstAccountKeyPem = settingsSvc.get().acmeAccountKeyPem;

        // Second issuance (e.g. a renewal) — newAccount is idempotent so the
        // same stub works; the account keypair must not be regenerated.
        acme.issueCertificate();
        assertThat(settingsSvc.get().acmeAccountKeyPem).isEqualTo(firstAccountKeyPem);
    }

    @Test
    void issueCertificate_noAcmeDomainConfigured_throwsWithoutCallingOut() {
        // tlsMode/acmeDomain left at reset() defaults (none/null).
        assertThatThrownBy(() -> acme.issueCertificate())
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("acmeDomain");
        assertThat(FakeHttpFetcher.calls).isEmpty();
    }
}
