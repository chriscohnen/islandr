package de.chriscohnen.islandr.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** backup-s3: the key an admin writes down must open the backup, and nothing else may. */
class BackupCryptoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aWrittenDownKeyParsesBackToTheSameBytes() {
        byte[] key = BackupCrypto.generateKey();
        String written = BackupCrypto.formatKey(key);
        assertThat(written).startsWith("IBK1-").matches("IBK1(-[A-Z2-7]{4}){14}");
        assertThat(BackupCrypto.parseKey(written)).isEqualTo(key);
    }

    @Test
    void theKeyIsForgivingAboutHowItWasCopied() {
        byte[] key = BackupCrypto.generateKey();
        String sloppy = BackupCrypto.formatKey(key).toLowerCase().replace("-", "  ");
        assertThat(BackupCrypto.parseKey(sloppy)).isEqualTo(key);
    }

    @Test
    void aTypoIsCaughtByTheChecksumBeforeAnyRestoreIsTried() {
        String written = BackupCrypto.formatKey(BackupCrypto.generateKey());
        char c = written.charAt(8);
        String typo = written.substring(0, 8) + (c == 'A' ? 'B' : 'A') + written.substring(9);
        assertThatThrownBy(() -> BackupCrypto.parseKey(typo)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typo");
        assertThatThrownBy(() -> BackupCrypto.parseKey("IBK1-ABCD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupCrypto.parseKey("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoGeneratedKeysDiffer() {
        assertThat(BackupCrypto.generateKey()).isNotEqualTo(BackupCrypto.generateKey());
    }

    @Test
    void sealedBackupOpensWithTheKey_andShowsItsHeaderWithoutIt() {
        byte[] key = BackupCrypto.generateKey();
        byte[] plain = "{\"users\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] file = BackupCrypto.seal(plain, key, Map.of("host", "hub1", "islandr", "1.4.0"), mapper);

        assertThat(new String(file, StandardCharsets.ISO_8859_1)).doesNotContain("users");
        assertThat(BackupCrypto.readHeader(file, mapper)).containsEntry("host", "hub1").containsEntry("islandr", "1.4.0");
        assertThat(BackupCrypto.open(file, key)).isEqualTo(plain);
    }

    @Test
    void aWrongKeyChangesNothing_itIsRefusedBeforeAnyPlaintextExists() {
        byte[] file = BackupCrypto.seal("secret".getBytes(StandardCharsets.UTF_8), BackupCrypto.generateKey(),
                Map.of("host", "hub1"), mapper);
        assertThatThrownBy(() -> BackupCrypto.open(file, BackupCrypto.generateKey()))
                .isInstanceOf(BackupCrypto.WrongKeyException.class);
    }

    @Test
    void aSwappedHeaderBreaksTheFile_soTheListCannotBeFaked() {
        byte[] key = BackupCrypto.generateKey();
        byte[] file = BackupCrypto.seal("x".getBytes(StandardCharsets.UTF_8), key, Map.of("host", "aaaa"), mapper);
        // the header is JSON in the clear: change one letter of it
        String text = new String(file, StandardCharsets.ISO_8859_1).replace("aaaa", "bbbb");
        byte[] tampered = text.getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> BackupCrypto.open(tampered, key)).isInstanceOf(BackupCrypto.WrongKeyException.class);
    }

    @Test
    void somethingThatIsNotABackupIsSaidSo() {
        assertThatThrownBy(() -> BackupCrypto.readHeader("hello".getBytes(StandardCharsets.UTF_8), mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
