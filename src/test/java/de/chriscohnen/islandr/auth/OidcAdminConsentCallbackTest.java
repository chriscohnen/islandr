package de.chriscohnen.islandr.auth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Issue #81: Microsoft sends the browser back from the admin-consent flow with
 * {@code admin_consent=True}, no authorization code and no state cookie —
 * because no login was ever started. Run through the ordinary callback path,
 * that produced {@code state mismatch (CSRF protection)} for a consent that had
 * in fact succeeded, which is what the operator saw and read as a broken client
 * secret.
 */
@QuarkusTest
class OidcAdminConsentCallbackTest {

    @Test
    void adminConsentReturn_isReportedAsConsent_notAsACsrfFailure() {
        given().redirects().follow(false)
                .when().get("/api/v1/auth/oidc/microsoft/callback?admin_consent=True&tenant=some-tenant")
                .then().statusCode(303)
                .header("Location", containsString("consent=granted"))
                .header("Location", not(containsString("error")));
    }

    @Test
    void declinedConsent_saysSo() {
        given().redirects().follow(false)
                .when().get("/api/v1/auth/oidc/microsoft/callback?admin_consent=False"
                        + "&error=access_denied&error_description=user+declined")
                .then().statusCode(303)
                .header("Location", containsString("consent=declined"));
    }

    /** An ordinary login callback still has to fail the CSRF check. */
    @Test
    void loginCallbackWithoutState_stillFailsTheCsrfCheck() {
        given().redirects().follow(false)
                .when().get("/api/v1/auth/oidc/microsoft/callback?code=abc&state=xyz")
                .then().statusCode(303)
                .header("Location", containsString("/login?error="));
    }
}
