package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The portal needs to know whether browser-based RDP is enabled so it can show or
 * hide the "open in browser" button — end users cannot read the admin-only
 * /settings endpoint, so the flag travels with their own /my-resources payload.
 * Reproduces the 0.10.0 gap where the flag was not exposed at all.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class MyAccessPortalConfigTest {

    @Inject SettingsService settings;

    @Transactional
    void setIronRdp(boolean enabled) {
        settings.get().ironRdpEnabled = enabled;
    }

    @Test
    void myResources_reportsIronRdpEnabled_reflectingTheSetting() {
        setIronRdp(true);
        given().when().get("/api/v1/acl/my-resources")
                .then().statusCode(200)
                .body("ironRdpEnabled", is(true));

        setIronRdp(false);
        given().when().get("/api/v1/acl/my-resources")
                .then().statusCode(200)
                .body("ironRdpEnabled", is(false));
    }
}
