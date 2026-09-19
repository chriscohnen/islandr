package de.chriscohnen.islandr.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.webauthn.Authenticator;
import io.vertx.ext.auth.webauthn.RelyingParty;
import io.vertx.ext.auth.webauthn.WebAuthn;
import io.vertx.ext.auth.webauthn.WebAuthnCredentials;
import io.vertx.ext.auth.webauthn.WebAuthnOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The WebAuthn ceremonies, over the Vert.x engine (ADR-0028).
 *
 * <p>The engine does the parts that are cryptography and format: challenge
 * generation, CBOR decoding, attestation handling, signature verification.
 * Everything that is a decision about this product — which credentials exist,
 * whether a counter is allowed to be what it is, what a successful assertion
 * issues — stays here and in {@link WebAuthnCredentialStore}, ending in the
 * same {@code Session} row every other login produces.
 *
 * <p>One engine per relying-party id, built on first use and kept: the id is
 * part of the options, and it varies with the name the console is reached
 * under.
 */
@ApplicationScoped
public class WebAuthnService {

    private static final Logger LOG = Logger.getLogger(WebAuthnService.class);

    /** The ceremony runs in a browser dialog; five seconds is already generous
     *  for the engine's own work and keeps a wedged call from holding a request
     *  thread. */
    private static final long CEREMONY_TIMEOUT_SECONDS = 5;

    @Inject Vertx vertx;
    @Inject WebAuthnCredentialStore store;
    @Inject WebAuthnChallenges challenges;

    private final Map<String, WebAuthn> engines = new ConcurrentHashMap<>();

    /** Thrown for every ceremony failure. Deliberately one type with a plain
     *  message: distinguishing "no such credential" from "bad signature" in the
     *  response would answer questions the caller has not earned. */
    public static class CeremonyFailedException extends RuntimeException {
        public CeremonyFailedException(String message, Throwable cause) { super(message, cause); }
        public CeremonyFailedException(String message) { super(message); }
    }

    private WebAuthn engineFor(String rpId) {
        return engines.computeIfAbsent(rpId, id -> {
            WebAuthnOptions options = new WebAuthnOptions()
                    .setRelyingParty(new RelyingParty().setName("Islandr").setId(id))
                    .setTimeoutInMilliseconds(WebAuthnChallenges.TTL.toMillis());
            return WebAuthn.create(vertx, options)
                    // The engine asks for the credentials it may consider. It
                    // only ever sees the ones belonging to this rp, because a
                    // credential registered under another name cannot be used
                    // here and offering it would be a dead end.
                    .authenticatorFetcher(query -> Future.succeededFuture(fetch(id, query)))
                    // The engine reports the counter it verified; the rule about
                    // what an acceptable counter is lives in the store.
                    .authenticatorUpdater(a -> {
                        try {
                            store.recordAssertion(a.getCredID(), a.getCounter());
                            return Future.succeededFuture();
                        } catch (RuntimeException ex) {
                            return Future.failedFuture(ex);
                        }
                    });
        });
    }

    private List<Authenticator> fetch(String rpId, Authenticator query) {
        List<WebAuthnCredential> rows = query.getCredID() != null
                ? oneByCredId(rpId, query.getCredID())
                : WebAuthnCredential.forSubjectAndRp(WebAuthnCredential.LOCAL_ADMIN, rpId);
        return rows.stream().map(c -> new Authenticator()
                .setUserName(c.subject)
                .setCredID(c.credentialId)
                .setPublicKey(c.publicKey)
                .setCounter(c.signCount)).toList();
    }

    private List<WebAuthnCredential> oneByCredId(String rpId, String credId) {
        WebAuthnCredential c = WebAuthnCredential.byCredentialId(credId);
        return (c != null && rpId.equals(c.rpId)) ? List.of(c) : List.of();
    }

    /** Options for enrolling a new authenticator. The returned JSON goes to the
     *  browser unchanged; the challenge inside it is remembered here. */
    public JsonObject registerChallenge(String rpId, String displayName) {
        requireName(rpId);
        JsonObject user = new JsonObject()
                .put("id", WebAuthnCredential.LOCAL_ADMIN)
                .put("rawId", WebAuthnCredential.LOCAL_ADMIN)
                .put("name", WebAuthnCredential.LOCAL_ADMIN)
                .put("displayName", displayName == null ? "Islandr recovery admin" : displayName);
        JsonObject options = await(engineFor(rpId).createCredentialsOptions(user), "register challenge");
        challenges.put(WebAuthnCredential.LOCAL_ADMIN, rpId, options.getString("challenge"));
        return options;
    }

    /** Options for signing in with an already-enrolled authenticator. */
    public JsonObject loginChallenge(String rpId) {
        requireName(rpId);
        if (!store.hasUsableFrom(WebAuthnCredential.LOCAL_ADMIN, rpId)) {
            throw new CeremonyFailedException("no security key is registered for this address");
        }
        JsonObject options = await(
                engineFor(rpId).getCredentialsOptions(WebAuthnCredential.LOCAL_ADMIN), "login challenge");
        challenges.put(WebAuthnCredential.LOCAL_ADMIN, rpId, options.getString("challenge"));
        return options;
    }

    /**
     * Verifies either ceremony. The engine authenticates; on a registration it
     * also hands back the new credential, which the store then owns.
     *
     * @return the credential id that was verified
     */
    public String verify(String rpId, String origin, JsonObject browserResponse, String label,
                         boolean registration) {
        requireName(rpId);
        String challenge = challenges.consume(WebAuthnCredential.LOCAL_ADMIN, rpId);
        if (challenge == null) {
            throw new CeremonyFailedException("no challenge is pending — start again");
        }
        WebAuthnCredentials credentials = new WebAuthnCredentials()
                .setOrigin(origin)
                .setDomain(rpId)
                .setChallenge(challenge)
                .setUsername(registration ? WebAuthnCredential.LOCAL_ADMIN : null)
                .setWebauthn(browserResponse);

        var user = await(engineFor(rpId).authenticate(credentials), registration ? "registration" : "assertion");
        String credentialId = browserResponse.getString("id");
        if (registration) {
            Authenticator fresh = newlyRegistered(rpId, credentialId);
            store.register(WebAuthnCredential.LOCAL_ADMIN, rpId, credentialId,
                    fresh.getPublicKey(), fresh.getCounter(), label);
        }
        LOG.infof("webauthn: %s succeeded for %s at %s",
                registration ? "registration" : "assertion", user.subject(), rpId);
        return credentialId;
    }

    /** After a registration the engine has already pushed the new authenticator
     *  through the updater, which for an unknown credential is a no-op in this
     *  design — so it is read back here rather than guessed at. */
    private Authenticator newlyRegistered(String rpId, String credentialId) {
        List<Authenticator> found = fetch(rpId, new Authenticator().setCredID(credentialId));
        if (!found.isEmpty()) return found.get(0);
        throw new CeremonyFailedException("the authenticator did not return a usable credential");
    }

    private static void requireName(String rpId) {
        if (rpId == null || rpId.isBlank()) {
            throw new CeremonyFailedException(
                    "security keys need a hostname — this console was reached by address");
        }
    }

    private static <T> T await(Future<T> future, String what) {
        try {
            return future.toCompletionStage().toCompletableFuture()
                    .get(CEREMONY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new CeremonyFailedException(what + " failed: " + cause.getMessage(), cause);
        }
    }
}
