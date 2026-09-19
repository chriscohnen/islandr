package de.chriscohnen.islandr.tls;

import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class HubCertificateResourceTest {

    @Inject HubCertificateService hubCerts;

    @AfterEach
    @Transactional
    void restore() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.tlsMode = "none";
        s.tlsCertPem = null;
        s.tlsKeyPem = null;
        s.dnsHubAlias = null;
    }

    @Test
    void generatingReturnsTheFingerprintAndTheNamesItCovers() {
        given().when().post("/api/v1/settings/tls/self-signed")
                .then().statusCode(200)
                .body("tlsMode", is("selfsigned"))
                .body("tlsFingerprint", matchesPattern("([0-9A-F]{2}:){31}[0-9A-F]{2}"))
                .body("hubCertNames", notNullValue())
                .body("tlsNamesOutOfDate", is(false));
    }

    @Test
    void addingAnAliasAfterwardsIsReportedAsOutOfDate() {
        given().when().post("/api/v1/settings/tls/self-signed").then().statusCode(200);
        setAlias("konsole.firma.de");

        // The symptom of not saying so is a second browser warning nobody
        // connects to a settings change made weeks earlier.
        assertThat(hubCerts.namesOutOfDate(currentSettings()))
                .as("the stored certificate no longer covers every name the hub answers for")
                .isTrue();
    }

    @Test
    void aRealCertificateIsNeverReplacedByAnUntrustedOne() {
        setManagedMode();

        given().when().post("/api/v1/settings/tls/self-signed")
                .then().statusCode(500);

        assertThat(currentSettings().tlsMode)
                .as("a downgrade from a trusted certificate is never what this click meant")
                .isEqualTo("managed");
    }

    @Transactional
    void setAlias(String alias) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsHubAlias = alias;
    }

    @Transactional
    void setManagedMode() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.tlsMode = "managed";
    }

    @Transactional
    Settings currentSettings() {
        return Settings.findById(Settings.SINGLETON_ID);
    }
}
