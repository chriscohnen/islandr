package de.chriscohnen.islandr.crypto;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hashes and verifies local user passwords with PBKDF2WithHmacSHA256 (F-01a).
 * Pure JDK crypto — no dependency. Output is a self-describing PHC-style string
 * {@code pbkdf2$sha256$<iterations>$<base64-salt>$<base64-hash>}, so the iteration
 * count and salt travel with the hash and can be raised later without a migration.
 *
 * <p>This is <em>not</em> the ENV bootstrap admin path ({@link de.chriscohnen.islandr.auth.AdminBootstrap}
 * keeps its in-memory SHA-256 compare); it is for DB-stored local user passwords.
 */
@ApplicationScoped
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2$sha256$";

    private final SecureRandom random = new SecureRandom();

    /** Hash a raw password with a fresh random salt; returns the PHC string to store. */
    public String hash(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = pbkdf2(raw, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + b64(salt) + "$" + b64(derived);
    }

    /**
     * Verify a raw password against a stored PHC string. Constant-time on the
     * derived key; returns {@code false} (never throws) for null or malformed input.
     */
    public boolean verify(String raw, String phc) {
        if (raw == null || phc == null || !phc.startsWith(PREFIX)) {
            return false;
        }
        String[] parts = phc.split("\\$"); // pbkdf2 / sha256 / iterations / salt / hash
        if (parts.length != 5) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[2]);
            salt = Base64.getDecoder().decode(parts[3]);
            expected = Base64.getDecoder().decode(parts[4]);
        } catch (RuntimeException e) {
            return false;
        }
        byte[] actual = pbkdf2(raw, salt, iterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] pbkdf2(String raw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable in this JVM", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
