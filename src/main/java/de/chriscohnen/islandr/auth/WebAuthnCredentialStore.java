package de.chriscohnen.islandr.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Bookkeeping for registered authenticators — the part of WebAuthn that is
 * islandr's own rather than the engine's (ADR-0028).
 *
 * <p>The signature counter lives here because it is the one check a WebAuthn
 * library cannot make alone: it needs the value seen last time, and that is
 * state only the relying party holds.
 */
@ApplicationScoped
public class WebAuthnCredentialStore {

    private static final Logger LOG = Logger.getLogger(WebAuthnCredentialStore.class);

    /** Refusing an assertion is a security decision, so it gets its own type
     *  rather than a boolean a caller can forget to check. */
    public static class CloneSuspectedException extends RuntimeException {
        public CloneSuspectedException(String message) { super(message); }
    }

    @Transactional
    public WebAuthnCredential register(String subject, String rpId, String credentialId,
                                       String publicKey, long signCount, String label) {
        if (credentialId == null || credentialId.isBlank()) {
            throw new IllegalArgumentException("credentialId is required");
        }
        if (WebAuthnCredential.byCredentialId(credentialId) != null) {
            // Registering the same authenticator twice is a user mistake, not an
            // attack — but silently keeping two rows would mean a counter split
            // across them, and then neither is authoritative.
            throw new IllegalStateException("this authenticator is already registered");
        }
        if (rpId == null || rpId.isBlank()) {
            // Reached by IP: there is no registrable domain, so there is nothing
            // a credential could be bound to. Refusing here beats storing a row
            // that can never authenticate anyone.
            throw new IllegalArgumentException(
                    "this host cannot carry a security key — reach the console by name first");
        }
        WebAuthnCredential c =
                WebAuthnCredential.createNew(subject, rpId, credentialId, publicKey, signCount, label);
        c.persist();
        LOG.infof("webauthn: credential registered for %s (%s)", subject,
                c.label == null ? "unlabelled" : c.label);
        return c;
    }

    public List<WebAuthnCredential> list(String subject) {
        return WebAuthnCredential.forSubject(subject);
    }

    /** True when this subject can authenticate with a key at all — what the
     *  login screen needs to decide whether to offer it. */
    public boolean hasAny(String subject) {
        return WebAuthnCredential.count("subject", subject) > 0;
    }

    /** True when a key can be used <em>from this host</em>. Different question:
     *  a registered key that belongs to another name is not usable here, and
     *  offering it would be a dead end the browser explains to nobody. */
    public boolean hasUsableFrom(String subject, String rpId) {
        return rpId != null && WebAuthnCredential.count("subject = ?1 and rpId = ?2", subject, rpId) > 0;
    }

    /**
     * Records a successful assertion and enforces the counter rule.
     *
     * <p>A counter that went backwards or stood still is the signature of a
     * cloned authenticator: the genuine one has moved on, the copy has not.
     * Refusing here is the whole point of storing the counter.
     *
     * <p>The exception is a counter that is zero on both sides. Plenty of
     * authenticators — most platform ones — never implement a counter and
     * report 0 forever. Treating that as a clone would lock out the majority of
     * keys in use, so a perpetual zero is accepted and simply carries no
     * clone-detection value.
     */
    @Transactional
    public void recordAssertion(String credentialId, long presentedCount) {
        WebAuthnCredential c = WebAuthnCredential.byCredentialId(credentialId);
        if (c == null) throw new IllegalArgumentException("unknown credential");
        applyCounter(c, presentedCount);
    }

    private void applyCounter(WebAuthnCredential c, long presentedCount) {
        boolean counterlessAuthenticator = presentedCount == 0 && c.signCount == 0;
        if (!counterlessAuthenticator && presentedCount <= c.signCount) {
            LOG.warnf("webauthn: assertion refused for %s — counter did not advance (%d <= %d)",
                    c.subject, presentedCount, c.signCount);
            throw new CloneSuspectedException(
                    "signature counter did not advance — the authenticator may have been cloned");
        }
        c.signCount = presentedCount;
        c.lastUsedAt = Instant.now();
    }

    /**
     * The engine's updater callback fires for both ceremonies and cannot tell
     * them apart itself — only the credential's presence here can. A
     * registration reaches this as an unseen id, carrying the public key and
     * counter the engine just verified from the attestation, so it is created
     * here rather than in {@link #register}, which the caller can no longer
     * reach in time: the row needs to exist before the engine's own future
     * resolves, and only then can a label be attached to it.
     */
    @Transactional
    public void upsertFromCeremony(String rpId, String subject, String credentialId,
                                   String publicKey, long counter) {
        WebAuthnCredential c = WebAuthnCredential.byCredentialId(credentialId);
        if (c == null) {
            WebAuthnCredential.createNew(subject, rpId, credentialId, publicKey, counter, null).persist();
            return;
        }
        applyCounter(c, counter);
    }

    /** Attaches the label chosen at registration time, once the ceremony's own
     *  upsert has created the row. A no-op label is left as none rather than
     *  overwriting a name with blank. */
    @Transactional
    public void setLabel(String credentialId, String label) {
        if (label == null || label.isBlank()) return;
        WebAuthnCredential c = WebAuthnCredential.byCredentialId(credentialId);
        if (c != null) c.label = label.strip();
    }

    /**
     * Removes a credential.
     *
     * <p>Removing the last one is allowed: the password path still exists, and
     * refusing would trap an admin who is replacing a lost key. It is logged,
     * because "the account that recovers other accounts has no second factor
     * any more" is worth being able to find later.
     */
    @Transactional
    public boolean remove(String subject, String id) {
        WebAuthnCredential c = WebAuthnCredential.findById(id);
        if (c == null || !c.subject.equals(subject)) return false;
        c.delete();
        if (!hasAny(subject)) {
            LOG.infof("webauthn: last credential removed for %s — password login is the only path again", subject);
        }
        return true;
    }
}
