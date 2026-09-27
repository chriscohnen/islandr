package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.audit.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0028 answers the lockout risk R-188 with "an offline command on the hub
 * clears the credentials". Until now that command did not exist: the only way
 * to remove one was through an authenticated session, which is unreachable
 * precisely when you cannot sign in any more.
 *
 * <p>The escape hatch is an environment variable read at startup, not a CLI
 * flag — the binary is a server with no command mode, and everything else
 * about this product is operated the same way.
 */
@QuarkusTest
class WebAuthnResetTest {

    @Inject WebAuthnReset reset;
    @Inject WebAuthnCredentialStore store;

    @BeforeEach
    void clean() {
        wipe();
    }

    @Transactional
    void wipe() {
        WebAuthnCredential.deleteAll();
    }

    @Transactional
    void seedCredential() {
        store.register(WebAuthnCredential.LOCAL_ADMIN, "hub.islandr.internal",
                "cred-" + UUID.randomUUID(), "pubkey", 0, "YubiKey");
    }

    @Test
    void clearsEveryCredentialOfTheRecoveryAdminWhenRequested() {
        seedCredential();
        seedCredential();
        assertThat(store.hasAny(WebAuthnCredential.LOCAL_ADMIN)).isTrue();

        int removed = reset.clearRecoveryAdminCredentials();

        assertThat(removed).isEqualTo(2);
        assertThat(store.hasAny(WebAuthnCredential.LOCAL_ADMIN)).isFalse();
    }

    /** ADR-0028 requires it: whoever runs the reset is not necessarily the one
     *  who notices, so it has to leave a trace. */
    @Test
    void writesAnAuditEntry() {
        seedCredential();
        long before = auditCount();

        reset.clearRecoveryAdminCredentials();

        assertThat(auditCount()).isGreaterThan(before);
    }

    /** Running it on a hub with no keys registered is not an error — an
     *  operator reaching for the escape hatch should not have to know whether
     *  it was needed. */
    @Test
    void isHarmlessWhenNothingIsRegistered() {
        assertThat(reset.clearRecoveryAdminCredentials()).isZero();
    }

    @Transactional
    long auditCount() {
        return AuditLog.count("action", "auth.webauthn_reset");
    }
}
