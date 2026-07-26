package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.crypto.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests the Origin Server Certificate CSR-generation flow (#42): generate a
 * private key + CSR via {@code POST /api/v1/settings/tls/csr}, then complete
 * it later by uploading only the CA-signed certificate — islandr already has
 * the matching private key, so {@code PUT /api/v1/settings/tls} doesn't need
 * it pasted again ({@link de.chriscohnen.islandr.tls.TlsService#resolveKeyPem}).
 *
 * <p>Own {@code @BeforeEach}/{@code @AfterEach} reset rather than the
 * {@code @Order}-sequenced convention {@link SettingsTlsTest} uses — this
 * class doesn't need to build on a running sequence, and resets the shared
 * settings singleton row before and after so neither class disturbs the other
 * (same convention as {@code AcmeServiceTest}).
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class TlsCsrFlowTest {

    @Inject SettingsService settingsSvc;
    @Inject EncryptionService encSvc;

    private static String fixture(String name) {
        try (InputStream in = TlsCsrFlowTest.class.getResourceAsStream("/tls-fixtures/" + name)) {
            if (in == null) throw new IllegalStateException("test fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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
        s.tlsCertPem = null;
        s.tlsKeyPem = null;
        s.acmeDomain = null;
        s.acmeAccountKeyPem = null;
        s.acmeAccountPubKey = null;
        s.acmeAccountUrl = null;
        s.acmeLastAttemptAt = null;
        s.acmeLastRenewalAt = null;
        s.acmeLastError = null;
        s.pendingCsrPem = null;
        s.pendingKeyPem = null;
        s.pendingCsrCreatedAt = null;
    }

    /** Sets a pending key directly (bypassing the real generate-CSR endpoint,
     *  whose own crypto is covered by {@code OriginCsrServiceTest}) — this class
     *  is testing the upload-side pairing/clearing logic, so a known fixture
     *  keypair ({@code valid-key.pem}/{@code valid-cert.pem}, already trusted
     *  by {@link SettingsTlsTest}) stands in for a freshly-generated one. */
    @Transactional
    void setPendingKey(String keyPem) {
        Settings s = settingsSvc.get();
        s.pendingKeyPem = encSvc.isConfigured() ? encSvc.encrypt(keyPem) : keyPem;
        s.pendingCsrPem = "-----BEGIN CERTIFICATE REQUEST-----\nfake\n-----END CERTIFICATE REQUEST-----\n";
        s.pendingCsrCreatedAt = Instant.now();
    }

    @Test
    void generateCsr_returnsCsrAndPersistsPending() {
        given().contentType("application/json")
                .body(Map.of("domain", "example.com"))
                .when().post("/api/v1/settings/tls/csr")
                .then().statusCode(200)
                .body("pendingCsrPem", startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
                .body("pendingCsrCreatedAt", notNullValue());

        given().when().get("/api/v1/settings")
                .then().statusCode(200)
                .body("pendingCsrPem", startsWith("-----BEGIN CERTIFICATE REQUEST-----"));
    }

    @Test
    void generateCsr_blankDomain_rejected() {
        given().contentType("application/json")
                .body(Map.of("domain", ""))
                .when().post("/api/v1/settings/tls/csr")
                .then().statusCode(400);
    }

    @Test
    void uploadTls_certOnly_noPendingKey_rejected() {
        given().contentType("application/json")
                .body(Map.of("pem", fixture("valid-cert.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(400);
    }

    @Test
    void uploadTls_certOnly_matchingPendingKey_completesAndClearsPending() {
        setPendingKey(fixture("valid-key.pem"));

        given().contentType("application/json")
                .body(Map.of("pem", fixture("valid-cert.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(200)
                .body("tlsMode", equalTo("managed"))
                .body("pendingCsrPem", nullValue());

        given().when().get("/api/v1/settings")
                .then().statusCode(200)
                .body("pendingCsrPem", nullValue());
    }

    @Test
    void uploadTls_certOnly_pendingKeyDoesNotMatch_rejected() {
        // mismatched-key.pem is, by definition (see SettingsTlsTest), not
        // valid-cert.pem's key — same wrong-pairing fixture relationship, just
        // with the pending key on the wrong side of the mismatch this time.
        setPendingKey(fixture("mismatched-key.pem"));

        given().contentType("application/json")
                .body(Map.of("pem", fixture("valid-cert.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(400);
    }

    @Test
    void ownKeyAndCertUpload_clearsAnyPendingCsr() {
        setPendingKey(fixture("valid-key.pem"));

        given().contentType("application/json")
                .body(Map.of("pem", fixture("valid-cert.pem") + "\n" + fixture("valid-key.pem")))
                .when().put("/api/v1/settings/tls")
                .then().statusCode(200)
                .body("tlsMode", equalTo("managed"))
                .body("pendingCsrPem", nullValue());
    }

    @Test
    void enablingAcme_clearsAnyPendingCsr() {
        setPendingKey(fixture("valid-key.pem"));

        // Issuance itself will fail (fake domain, no real ACME reachability in
        // this profile) — irrelevant here; enableAcme persists mode/domain and
        // clears the pending CSR before that attempt even runs.
        given().contentType("application/json")
                .body(Map.of("domain", "islandr-csr-flow-test.invalid"))
                .when().put("/api/v1/settings/acme");

        Settings after = settingsSvc.get();
        assertThat(after.tlsMode).isEqualTo("acme");
        assertThat(after.pendingCsrPem).isNull();
        assertThat(after.pendingKeyPem).isNull();
        assertThat(after.pendingCsrCreatedAt).isNull();
    }
}
