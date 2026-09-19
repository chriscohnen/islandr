package de.chriscohnen.islandr.auth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * What can be asserted without a real authenticator: that a challenge is
 * issued, that the ceremony refuses the states it must refuse, and that the
 * console can tell a login screen whether offering a key would be a dead end.
 *
 * <p>The cryptographic half — a browser producing an attestation this server
 * accepts — is not reproducible here. It is the Vert.x engine's job, and
 * claiming to have verified it from a test that never ran a browser would be
 * the kind of "must be fine" this project's own loop instructions forbid.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class WebAuthnResourceTest {

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        WebAuthnCredential.deleteAll();
    }

    @Test
    void aRegistrationChallengeIsIssuedForANamedHost() {
        given().header("Host", "hub.islandr.internal")
                .contentType("application/json").body("{\"label\": \"YubiKey\"}")
                .when().post("/api/v1/auth/webauthn/register/challenge")
                .then().statusCode(200)
                .body("challenge", notNullValue())
                .body("rp.id", is("hub.islandr.internal"));
    }

    @Test
    void aHostReachedByAddressCannotEnrolAKey() {
        // Not a missing feature: WebAuthn binds a credential to a registrable
        // domain, and an IP is not one. Saying so beats a browser dialog that
        // fails with nothing an admin can act on.
        given().header("Host", "10.77.140.1:8443")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/auth/webauthn/register/challenge")
                .then().statusCode(500);
    }

    @Test
    void aLoginChallengeIsRefusedWhenNoKeyIsRegisteredForThisName() {
        given().header("Host", "hub.islandr.internal")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/auth/webauthn/login/challenge")
                .then().statusCode(500);
    }

    @Test
    void verifyingWithoutAPendingChallengeIsRefused() {
        given().header("Host", "hub.islandr.internal")
                .header("Origin", "https://hub.islandr.internal")
                .contentType("application/json")
                .body("{\"response\": {\"id\": \"whatever\"}}")
                .when().post("/api/v1/auth/webauthn/login/verify")
                .then().statusCode(500);
    }

    @Test
    void verifyingWithoutTheAuthenticatorsResponseIsABadRequest() {
        given().header("Host", "hub.islandr.internal")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/auth/webauthn/login/verify")
                .then().statusCode(400);
    }

    @Test
    void availabilityDistinguishesNoKeyFromAKeyForAnotherName() {
        given().header("Host", "hub.islandr.internal")
                .when().get("/api/v1/auth/webauthn/availability")
                .then().statusCode(200)
                .body("hostSupportsKeys", is(true))
                .body("registered", is(false))
                .body("usableHere", is(false));

        registerElsewhere();

        // The browser would simply not offer the key and say nothing. This is
        // what lets the login screen explain it instead.
        given().header("Host", "hub.islandr.internal")
                .when().get("/api/v1/auth/webauthn/availability")
                .then().statusCode(200)
                .body("registered", is(true))
                .body("usableHere", is(false));
    }

    @Test
    void availabilityReportsThatAnAddressCannotCarryKeysAtAll() {
        given().header("Host", "10.77.140.1:8443")
                .when().get("/api/v1/auth/webauthn/availability")
                .then().statusCode(200)
                .body("hostSupportsKeys", is(false));
    }

    @Test
    void theCredentialListShowsWhichNameEachKeyBelongsTo() {
        registerElsewhere();

        given().when().get("/api/v1/auth/webauthn")
                .then().statusCode(200)
                .body("[0].rpId", is("konsole.firma.de"));
    }

    @Transactional
    void registerElsewhere() {
        WebAuthnCredential.createNew(WebAuthnCredential.LOCAL_ADMIN, "konsole.firma.de",
                "cred-elsewhere", "key-e", 0, "Schlüssel vom anderen Namen").persist();
    }
}
