package de.chriscohnen.islandr.crypto;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies AES-256-GCM round-trip, stored format, and error handling.
 * Runs with the %test profile which supplies a fixed zero-key via
 * {@code islandr.encryption.key} — see application.properties.
 */
@QuarkusTest
class EncryptionServiceTest {

    @Inject EncryptionService svc;

    @Test
    void isConfigured_whenTestKeyIsSet() {
        assertThat(svc.isConfigured()).isTrue();
    }

    @Test
    void encrypt_producesEncPrefix() {
        String stored = svc.encrypt("someSecretKey123");
        assertThat(stored).startsWith("enc$");
    }

    @Test
    void isEncrypted_detectsPrefix() {
        assertThat(svc.isEncrypted("enc$AAAA")).isTrue();
        assertThat(svc.isEncrypted("plaintext==")).isFalse();
        assertThat(svc.isEncrypted(null)).isFalse();
    }

    @Test
    void encryptDecrypt_roundTrip() {
        // WireGuard keys are 44-char base64 strings
        String original = "YGa2D3c4pRmHmM9fkLQ7b8yW5f+5A7hKpVXZkm3oFE4=";
        String stored = svc.encrypt(original);
        String recovered = svc.decrypt(stored);
        assertThat(recovered).isEqualTo(original);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime() {
        String original = "YGa2D3c4pRmHmM9fkLQ7b8yW5f+5A7hKpVXZkm3oFE4=";
        String first = svc.encrypt(original);
        String second = svc.encrypt(original);
        // Different IVs → different ciphertext
        assertThat(first).isNotEqualTo(second);
        // Both decrypt to the same plaintext
        assertThat(svc.decrypt(first)).isEqualTo(original);
        assertThat(svc.decrypt(second)).isEqualTo(original);
    }

    @Test
    void decrypt_corruptedInput_throwsIllegalState() {
        assertThatThrownBy(() -> svc.decrypt("enc$notvalidbase64!!!"))
                .isInstanceOf(IllegalStateException.class);
    }
}
