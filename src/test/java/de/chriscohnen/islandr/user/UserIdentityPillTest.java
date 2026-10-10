package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.identity.OidcCustomProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * GET /api/v1/users exposes, per user, whether they can log in locally
 * (a password is set), via which OIDC provider (if any), and — for the
 * generic/custom provider — its admin-given display name. Backs the
 * users-identity-pill: an admin scanning the user list should not have to
 * open each account to find out how it logs in.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class UserIdentityPillTest {

    @AfterEach
    @Transactional
    void cleanup() {
        User.delete("email like ?1", "%@identity-pill.test");
        OidcCustomProvider.delete("issuerUrl", "https://idp.identity-pill.test");
    }

    @Test
    void localOnlyUser_hasNoOidcFields() {
        String id = createUser("local@identity-pill.test");
        setPassword(id, "a-fine-password");

        given().when().get("/api/v1/users/" + id)
                .then().statusCode(200)
                .body("hasLocalPassword", is(true))
                .body("oidcProvider", nullValue())
                .body("oidcProviderLabel", nullValue());
    }

    @Test
    void userWithNoPasswordSet_hasLocalPasswordFalse() {
        String id = createUser("nopassword@identity-pill.test");

        given().when().get("/api/v1/users/" + id)
                .then().statusCode(200)
                .body("hasLocalPassword", is(false));
    }

    @Test
    void microsoftLinkedUser_reportsTheFixedLabel() {
        String id = createOidcUser("ms@identity-pill.test", "microsoft", null);

        given().when().get("/api/v1/users/" + id)
                .then().statusCode(200)
                .body("oidcProvider", is("microsoft"))
                .body("oidcProviderLabel", is("Microsoft"));
    }

    @Test
    void googleLinkedUser_reportsTheFixedLabel() {
        String id = createOidcUser("google@identity-pill.test", "google", null);

        given().when().get("/api/v1/users/" + id)
                .then().statusCode(200)
                .body("oidcProvider", is("google"))
                .body("oidcProviderLabel", is("Google"));
    }

    @Test
    void customProviderLinkedUser_reportsTheAdminGivenDisplayName() {
        String providerId = createCustomProvider("Keycloak (Ops-VPN)");
        String id = createOidcUser("custom@identity-pill.test", "custom", providerId);

        given().when().get("/api/v1/users/" + id)
                .then().statusCode(200)
                .body("oidcProvider", is("custom"))
                .body("oidcProviderLabel", is("Keycloak (Ops-VPN)"));
    }

    /** A user can have both — set a password, then also link an OIDC identity
     *  (or vice versa). Both facts must travel independently. */
    @Test
    void userWithBothAPasswordAndAnOidcLink_reportsBoth() {
        String id = createOidcUser("both@identity-pill.test", "microsoft", null);
        setPassword(id, "a-fine-password");

        given().when().get("/api/v1/users/" + id)
                .then().statusCode(200)
                .body("hasLocalPassword", is(true))
                .body("oidcProvider", is("microsoft"));
    }

    // -- helpers --------------------------------------------------------------

    @Transactional
    String createUser(String email) {
        User u = User.createNew("Test " + email, email);
        u.persist();
        return u.id;
    }

    @Transactional
    String createOidcUser(String email, String provider, String customProviderId) {
        User u = User.createNew("OIDC " + email, email);
        u.oidcProvider = provider;
        u.oidcSubject = provider + "-" + email;
        u.oidcCustomProviderId = customProviderId;
        u.persist();
        return u.id;
    }

    @Transactional
    String createCustomProvider(String displayName) {
        OidcCustomProvider p = new OidcCustomProvider();
        p.id = java.util.UUID.randomUUID().toString();
        p.displayName = displayName;
        p.issuerUrl = "https://idp.identity-pill.test";
        p.scopes = "openid email profile";
        p.enabled = true;
        p.createdAt = java.time.Instant.now();
        p.updatedAt = java.time.Instant.now();
        p.updatedBy = "test-admin";
        p.persist();
        return p.id;
    }

    void setPassword(String userId, String password) {
        given().contentType("application/json")
                .body("{ \"password\": \"" + password + "\" }")
                .when().put("/api/v1/users/" + userId + "/password")
                .then().statusCode(200);
    }
}
