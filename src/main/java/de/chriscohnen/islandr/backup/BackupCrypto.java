package de.chriscohnen.islandr.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

/**
 * The backup key and the file format (backup-s3).
 *
 * <p><b>The key.</b> 32 random bytes, written for a person to copy onto paper:
 * {@code IBK1-} and fourteen groups of four base32 letters, the last three
 * bytes of the 35 being a checksum, so a mistyped character is caught before a
 * restore is attempted rather than looking like a corrupt backup.
 *
 * <p><b>The file.</b> {@code ISLBAK01}, the header length, a small JSON header
 * (when, which host, which Islandr) in the clear, a nonce, and the AES-GCM
 * ciphertext. The header is not secret — the restore list shows it before any
 * key is typed — but it is bound into the GCM tag as additional data, so it
 * cannot be swapped without the file failing to open.
 */
final class BackupCrypto {

    static final String KEY_PREFIX = "IBK1-";
    private static final byte[] MAGIC = "ISLBAK01".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_BYTES = 32;
    private static final int CHECK_BYTES = 3;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private BackupCrypto() {}

    /** The key did not open the file (wrong key, or the file was changed). */
    static final class WrongKeyException extends RuntimeException {
        WrongKeyException() { super("wrong backup key, or the backup was changed"); }
    }

    // ── the key ─────────────────────────────────────────────────────────

    static byte[] generateKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static byte[] checksum(byte[] key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
            return Arrays.copyOf(h, CHECK_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code IBK1-XXXX-XXXX-…} — what the admin writes down. */
    static String formatKey(byte[] key) {
        byte[] all = new byte[KEY_BYTES + CHECK_BYTES];
        System.arraycopy(key, 0, all, 0, KEY_BYTES);
        System.arraycopy(checksum(key), 0, all, KEY_BYTES, CHECK_BYTES);
        String b32 = base32(all);
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < b32.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(b32, i, i + 4);
        }
        return sb.toString();
    }

    /** Accepts the key as written down: spaces, dashes and case do not matter. */
    static byte[] parseKey(String text) {
        if (text == null) throw new IllegalArgumentException("the backup key is empty");
        String s = text.toUpperCase().replaceAll("[\\s-]", "");
        if (s.startsWith("IBK1")) s = s.substring(4);
        if (s.length() != (KEY_BYTES + CHECK_BYTES) * 8 / 5) {
            throw new IllegalArgumentException("that is not a backup key (wrong length)");
        }
        byte[] all = unbase32(s);
        byte[] key = Arrays.copyOf(all, KEY_BYTES);
        if (!Arrays.equals(Arrays.copyOfRange(all, KEY_BYTES, all.length), checksum(key))) {
            throw new IllegalArgumentException("the backup key has a typo (checksum does not match)");
        }
        return key;
    }

    private static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(B32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    private static byte[] unbase32(String s) {
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bits = 0, at = 0;
        for (char c : s.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("that is not a backup key (unexpected character '" + c + "')");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[at++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    // ── the file ────────────────────────────────────────────────────────

    static byte[] seal(byte[] plaintext, byte[] key, Map<String, Object> header, ObjectMapper mapper) {
        try {
            byte[] head = mapper.writeValueAsBytes(header);
            byte[] aad = aad(head);
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(MAGIC.length + 4 + head.length + NONCE_BYTES + ct.length)
                    .put(MAGIC).putInt(head.length).put(head).put(nonce).put(ct).array();
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt the backup", e);
        }
    }

    /** The clear-text header — readable without the key. */
    static Map<String, Object> readHeader(byte[] file, ObjectMapper mapper) {
        try {
            return mapper.readValue(parse(file).head(), MAP);
        } catch (IOException e) {
            throw new IllegalArgumentException("this is not an Islandr backup", e);
        }
    }

    /** Decrypts; the GCM tag is checked here, before the caller sees a single byte. */
    static byte[] open(byte[] file, byte[] key) {
        Parsed p = parse(file);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, p.nonce()));
            c.updateAAD(aad(p.head()));
            return c.doFinal(p.ciphertext());
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongKeyException();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("could not decrypt the backup", e);
        }
    }

    private static byte[] aad(byte[] head) {
        return ByteBuffer.allocate(MAGIC.length + head.length).put(MAGIC).put(head).array();
    }

    private record Parsed(byte[] head, byte[] nonce, byte[] ciphertext) {}

    private static Parsed parse(byte[] file) {
        if (file == null || file.length < MAGIC.length + 4 + NONCE_BYTES
                || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("this is not an Islandr backup");
        }
        ByteBuffer b = ByteBuffer.wrap(file, MAGIC.length, file.length - MAGIC.length);
        int headLen = b.getInt();
        if (headLen < 0 || headLen > 64 * 1024 || b.remaining() < headLen + NONCE_BYTES) {
            throw new IllegalArgumentException("this is not an Islandr backup");
        }
        byte[] head = new byte[headLen];
        b.get(head);
        byte[] nonce = new byte[NONCE_BYTES];
        b.get(nonce);
        byte[] ct = new byte[b.remaining()];
        b.get(ct);
        return new Parsed(head, nonce, ct);
    }
}
