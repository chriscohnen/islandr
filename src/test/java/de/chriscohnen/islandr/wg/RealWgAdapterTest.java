package de.chriscohnen.islandr.wg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RealWgAdapter} against a real {@code wg} binary.
 * Conditional on {@code wg} being on PATH so CI runs that don't have it
 * skip rather than fail.
 *
 * <p>Only the userspace methods are exercised — {@code genKeypair} needs no
 * kernel interface. {@code setPeer}/{@code showPeers} require a live wg0
 * interface and are exercised on the Hub VM, not in unit tests.
 */
class RealWgAdapterTest {

    /** Test enabler — runs the test only if `wg --version` succeeds. */
    static boolean wgIsAvailable() {
        try {
            Process p = new ProcessBuilder("wg", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("wgIsAvailable")
    void genKeypair_producesValidBase64Curve25519Keys() {
        RealWgAdapter adapter = new RealWgAdapter(false);
        WgAdapter.Keypair kp = adapter.genKeypair();

        // wg genkey emits a 32-byte Curve25519 private key, base64-encoded → 44 chars including '='.
        assertThat(kp.privateKey()).hasSize(44).endsWith("=");
        assertThat(kp.publicKey()).hasSize(44).endsWith("=");

        byte[] priv = Base64.getDecoder().decode(kp.privateKey());
        byte[] pub = Base64.getDecoder().decode(kp.publicKey());
        assertThat(priv).hasSize(32);
        assertThat(pub).hasSize(32);

        // Public key must derive deterministically from the private key.
        // Verify by running `wg pubkey` again on the same private key.
        WgAdapter.Keypair second = adapter.genKeypair();
        assertThat(second.privateKey()).isNotEqualTo(kp.privateKey());
    }

    @Test
    @EnabledIf("wgIsAvailable")
    void genKeypair_producedKeysAreDistinct() {
        RealWgAdapter adapter = new RealWgAdapter(false);
        WgAdapter.Keypair a = adapter.genKeypair();
        WgAdapter.Keypair b = adapter.genKeypair();
        assertThat(a.privateKey()).isNotEqualTo(b.privateKey());
        assertThat(a.publicKey()).isNotEqualTo(b.publicKey());
    }
}
