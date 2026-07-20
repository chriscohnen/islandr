package de.chriscohnen.islandr.crypto;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for retained WireGuard private keys (ADR-0007 "encrypted" mode).
 *
 * <p>Stored format: {@code "enc$"} prefix + Base64(12-byte IV || ciphertext || 16-byte GCM tag).
 *
 * <p>Key delivery priority:
 * <ol>
 *   <li>File path from env {@code ISLANDR_ENCRYPTION_KEY_PATH} — intended for systemd-creds delivery
 *       ({@code LoadCredentialEncrypted=} in the service unit places the key at
 *       {@code /run/credentials/islandr.service/ENCRYPTION_KEY}).
 *   <li>Env var {@code ISLANDR_ENCRYPTION_KEY} (base64, 32 bytes) — fallback for Docker / dev.
 *   <li>Config property {@code islandr.encryption.key} — test profile only (never in production).
 * </ol>
 *
 * <p>If none is configured, {@link #isConfigured()} returns {@code false} and the
 * {@code encrypted} retention mode is unavailable. The {@code never} and {@code plaintext} modes
 * continue to work without a key.
 */
@ApplicationScoped
public class EncryptionService {

    private static final Logger LOG = Logger.getLogger(EncryptionService.class);
    private static final String PREFIX = "enc$";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private SecretKey key;

    @PostConstruct
    void init() {
        String path = System.getenv("ISLANDR_ENCRYPTION_KEY_PATH");
        if (path == null) {
            path = System.getProperty("islandr.encryption.key-path");
        }
        if (path != null) {
            try {
                byte[] raw = Files.readAllBytes(Path.of(path));
                String encoded = new String(raw, StandardCharsets.UTF_8).strip();
                key = new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES");
                LOG.info("EncryptionService: key loaded from file");
                return;
            } catch (Exception e) {
                LOG.errorf("EncryptionService: failed to load key from %s: %s", path, e.getMessage());
            }
        }
        String envKey = System.getenv("ISLANDR_ENCRYPTION_KEY");
        if (envKey != null && !envKey.isBlank()) {
            try {
                key = new SecretKeySpec(Base64.getDecoder().decode(envKey.strip()), "AES");
                LOG.info("EncryptionService: key loaded from env var");
                return;
            } catch (Exception e) {
                LOG.errorf("EncryptionService: ISLANDR_ENCRYPTION_KEY is not valid base64: %s", e.getMessage());
            }
        }
        // ConfigProvider.getConfig() is a runtime API call — no injection-point validation,
        // safe in Quarkus native. Used for the %test profile key; never set in production.
        String cfgKey = ConfigProvider.getConfig()
                .getOptionalValue("islandr.encryption.key", String.class)
                .orElse("");
        if (!cfgKey.isBlank()) {
            try {
                key = new SecretKeySpec(Base64.getDecoder().decode(cfgKey.strip()), "AES");
                LOG.debug("EncryptionService: key loaded from config property");
                return;
            } catch (Exception e) {
                LOG.errorf("EncryptionService: islandr.encryption.key is not valid base64: %s", e.getMessage());
            }
        }
        LOG.debug("EncryptionService: no key configured — encrypted retention mode unavailable");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[GCM_IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_LENGTH);
            System.arraycopy(ct, 0, out, GCM_IV_LENGTH, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(raw, 0, GCM_IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(raw, GCM_IV_LENGTH, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed — wrong key or corrupted data", e);
        }
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
