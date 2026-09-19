package de.chriscohnen.islandr.auth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of WebAuthn that is not the library's: which credentials exist, and
 * whether an assertion's signature counter is allowed to be what it is.
 *
 * <p>The counter check cannot live in the engine, because it needs the value
 * seen last time — state only the relying party holds.
 */
@QuarkusTest
class WebAuthnCredentialStoreTest {

    @Inject WebAuthnCredentialStore store;

    private static final String SUBJECT = WebAuthnCredential.LOCAL_ADMIN;
    private static final String RP = "hub.islandr.internal";

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        WebAuthnCredential.deleteAll();
    }

    @Test
    void severalAuthenticatorsCanBeRegistered() {
        store.register(SUBJECT, RP, "cred-a", "key-a", 0, "YubiKey am Schlüsselbund");
        store.register(SUBJECT, RP, "cred-b", "key-b", 0, "Ersatzschlüssel im Safe");

        // One credential makes handover impossible without a gap, and a lost key
        // with no second one is a lockout of the recovery account itself.
        assertThat(store.list(SUBJECT)).hasSize(2);
        assertThat(store.hasAny(SUBJECT)).isTrue();
    }

    @Test
    void registeringTheSameAuthenticatorTwiceIsRefused() {
        store.register(SUBJECT, RP, "cred-a", "key-a", 0, null);

        assertThatThrownBy(() -> store.register(SUBJECT, RP, "cred-a", "key-a", 0, null))
                .as("two rows would split the counter, and then neither is authoritative")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAdvancingCounterIsAccepted() {
        store.register(SUBJECT, RP, "cred-a", "key-a", 7, null);

        store.recordAssertion("cred-a", 8);

        assertThat(signCountOf("cred-a")).isEqualTo(8);
        assertThat(lastUsedOf("cred-a")).isNotNull();
    }

    @Test
    void aCounterThatStoodStillIsRefusedAsAPossibleClone() {
        store.register(SUBJECT, RP, "cred-a", "key-a", 7, null);

        assertThatThrownBy(() -> store.recordAssertion("cred-a", 7))
                .isInstanceOf(WebAuthnCredentialStore.CloneSuspectedException.class);
        assertThat(signCountOf("cred-a")).isEqualTo(7);
    }

    @Test
    void aCounterThatWentBackwardsIsRefused() {
        store.register(SUBJECT, RP, "cred-a", "key-a", 7, null);

        assertThatThrownBy(() -> store.recordAssertion("cred-a", 3))
                .isInstanceOf(WebAuthnCredentialStore.CloneSuspectedException.class);
    }

    @Test
    void anAuthenticatorThatNeverCountsIsNotTreatedAsACloneForever() {
        store.register(SUBJECT, RP, "cred-zero", "key-z", 0, null);

        // Most platform authenticators never implement a counter and report 0
        // on every assertion. Refusing that would lock out the majority of keys
        // in use; it simply carries no clone-detection value.
        store.recordAssertion("cred-zero", 0);
        store.recordAssertion("cred-zero", 0);

        assertThat(signCountOf("cred-zero")).isZero();
    }

    @Test
    void removingTheLastCredentialIsAllowed() {
        WebAuthnCredential c = store.register(SUBJECT, RP, "cred-a", "key-a", 0, null);

        // The password path still exists; refusing would trap an admin who is
        // replacing a key they no longer have.
        assertThat(store.remove(SUBJECT, c.id)).isTrue();
        assertThat(store.hasAny(SUBJECT)).isFalse();
    }

    @Test
    void aKeyRegisteredUnderAnotherNameIsNotUsableHere() {
        store.register(SUBJECT, "konsole.firma.de", "cred-elsewhere", "key-e", 0, null);

        // The browser does not offer it and gives no reason. Knowing that here
        // is what lets the console explain it instead of looking broken.
        assertThat(store.hasAny(SUBJECT)).isTrue();
        assertThat(store.hasUsableFrom(SUBJECT, RP)).isFalse();
        assertThat(store.hasUsableFrom(SUBJECT, "konsole.firma.de")).isTrue();
    }

    @Test
    void aHostWithoutANameCannotRegisterAKey() {
        assertThatThrownBy(() -> store.register(SUBJECT, null, "cred-x", "key-x", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCredentialBelongingToSomeoneElseIsNotRemoved() {
        WebAuthnCredential c = store.register(SUBJECT, RP, "cred-a", "key-a", 0, null);

        assertThat(store.remove("someone-else", c.id)).isFalse();
        assertThat(store.list(SUBJECT)).hasSize(1);
    }

    @Transactional
    long signCountOf(String credentialId) {
        return WebAuthnCredential.byCredentialId(credentialId).signCount;
    }

    @Transactional
    Object lastUsedOf(String credentialId) {
        return WebAuthnCredential.byCredentialId(credentialId).lastUsedAt;
    }
}
