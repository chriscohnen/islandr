package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.crypto.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * UC-GWS: service-account JSON is encrypted at rest when an encryption key is configured.
 * The %test profile supplies a fixed zero-key via islandr.encryption.key.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class GoogleWorkspaceSettingsEncryptionTest {

    static final String FAKE_SA_JSON =
            "{\"type\":\"service_account\",\"project_id\":\"test\",\"client_email\":\"sa@test.iam.gserviceaccount.com\"}";

    @Inject SettingsService settingsSvc;
    @Inject EncryptionService encSvc;

    @AfterEach
    void clearGwsConfig() {
        given().contentType("application/json")
                .body(Map.of())
                .when().put("/api/v1/settings/google-workspace")
                .then().statusCode(200);
    }

    @Test
    void saJson_isStoredEncrypted_whenEncryptionKeyIsConfigured() {
        assertThat(encSvc.isConfigured()).isTrue();

        given().contentType("application/json")
                .body(Map.of("serviceAccountJson", FAKE_SA_JSON, "impersonationEmail", "admin@test.com"))
                .when().put("/api/v1/settings/google-workspace")
                .then().statusCode(200)
                .body("googleWsConfigured", is(true));

        String stored = settingsSvc.get().googleWsServiceAccountJson;
        assertThat(encSvc.isEncrypted(stored))
                .as("SA JSON must be stored with enc$ prefix")
                .isTrue();
    }

    @Test
    void saJson_decryptsToOriginalValue() {
        given().contentType("application/json")
                .body(Map.of("serviceAccountJson", FAKE_SA_JSON, "impersonationEmail", "admin@test.com"))
                .when().put("/api/v1/settings/google-workspace")
                .then().statusCode(200);

        String stored = settingsSvc.get().googleWsServiceAccountJson;
        assertThat(encSvc.decrypt(stored)).isEqualTo(FAKE_SA_JSON);
    }

    @Test
    void clearingConfig_removesStoredJson() {
        given().contentType("application/json")
                .body(Map.of("serviceAccountJson", FAKE_SA_JSON, "impersonationEmail", "admin@test.com"))
                .when().put("/api/v1/settings/google-workspace")
                .then().statusCode(200);

        given().contentType("application/json")
                .body(Map.of())
                .when().put("/api/v1/settings/google-workspace")
                .then().statusCode(200)
                .body("googleWsConfigured", is(false));

        assertThat(settingsSvc.get().googleWsServiceAccountJson).isNull();
    }
}
