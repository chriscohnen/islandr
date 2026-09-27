package de.chriscohnen.islandr.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ceremony a response claims must not be the ceremony the server runs.
 *
 * <p>vertx-auth-webauthn picks between registration and assertion from
 * {@code clientDataJSON.type}, which the caller supplies — not from the
 * endpoint that was called. So a {@code webauthn.create} response posted to
 * the login endpoint runs the registration path, and a registration path that
 * is willing to create a credential for an unknown id hands an unauthenticated
 * caller both a session and a key that keeps working afterwards.
 *
 * <p>These tests stop before the cryptography on purpose: the server must
 * refuse the mismatch on the shape of the response alone, so a refusal cannot
 * depend on an attestation being malformed. A response that is rejected only
 * because its signature does not check out would still be one valid signature
 * away from a bypass.
 */
@QuarkusTest
class WebAuthnCeremonyConfusionTest {

    private static final String HOST = "hub.islandr.internal";
    private static final String ORIGIN = "https://" + HOST;

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        WebAuthnCredential.deleteAll();
    }

    /** One registered key, so that /login/challenge has something to answer for. */
    @Transactional
    void seedCredential() {
        WebAuthnCredential.createNew(WebAuthnCredential.LOCAL_ADMIN, HOST,
                "seed-credential-id", "seed-public-key", 1, "seeded").persist();
    }

    private static String clientData(String type, String challenge) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                new JsonObject()
                        .put("type", type)
                        .put("challenge", challenge)
                        .put("origin", ORIGIN)
                        .encode().getBytes());
    }

    private String loginChallenge() {
        return given().header("Host", HOST).contentType("application/json")
                .when().post("/api/v1/auth/webauthn/login/challenge")
                .then().statusCode(200)
                .extract().path("challenge");
    }

    private long credentialCount() {
        return WebAuthnCredential.count();
    }

    @Test
    void aRegistrationShapedResponseIsRefusedAtTheLoginEndpoint() {
        seedCredential();
        String challenge = loginChallenge();
        long before = credentialCount();

        String body = new JsonObject()
                .put("response", new JsonObject()
                        .put("id", "attacker-credential-id")
                        .put("rawId", "attacker-credential-id")
                        .put("type", "public-key")
                        .put("response", new JsonObject()
                                .put("clientDataJSON", clientData("webauthn.create", challenge))
                                .put("attestationObject", "irrelevant-the-shape-decides")))
                .encode();

        given().header("Host", HOST).header("Origin", ORIGIN)
                .contentType("application/json").body(body)
                .when().post("/api/v1/auth/webauthn/login/verify")
                .then().statusCode(401);

        assertEquals(before, credentialCount(),
                "a login attempt must never create a credential");
    }

    /** The mirror image: an assertion posted to the registration endpoint. It
     *  needs an admin session to reach at all, so it is the weaker of the two —
     *  but the same confusion, and the same rule refuses it. */
    @Test
    void anAssertionShapedResponseIsRefusedAtTheRegistrationEndpoint() {
        String body = new JsonObject()
                .put("label", "attacker")
                .put("response", new JsonObject()
                        .put("id", "attacker-credential-id")
                        .put("type", "public-key")
                        .put("response", new JsonObject()
                                .put("clientDataJSON", clientData("webauthn.get", "unused"))))
                .encode();

        given().header("Host", HOST).header("Origin", ORIGIN)
                .contentType("application/json").body(body)
                .when().post("/api/v1/auth/webauthn/register/verify")
                .then().statusCode(401);
    }

    /** A login challenge must not be spendable on a registration, even once the
     *  ceremony check above is in place — two locks, because this one also
     *  closes the reverse direction. */
    @Test
    void aLoginChallengeDoesNotSatisfyARegistration() {
        seedCredential();
        String challenge = loginChallenge();

        String body = new JsonObject()
                .put("label", "attacker")
                .put("response", new JsonObject()
                        .put("id", "attacker-credential-id")
                        .put("type", "public-key")
                        .put("response", new JsonObject()
                                .put("clientDataJSON", clientData("webauthn.create", challenge))
                                .put("attestationObject", "irrelevant")))
                .encode();

        given().header("Host", HOST).header("Origin", ORIGIN)
                .contentType("application/json").body(body)
                .when().post("/api/v1/auth/webauthn/register/verify")
                .then().statusCode(401);

        assertEquals(1, credentialCount(), "only the seeded credential may exist");
    }
}
