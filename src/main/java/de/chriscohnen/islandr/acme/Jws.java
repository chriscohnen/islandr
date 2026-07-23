package de.chriscohnen.islandr.acme;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ES256 (EC P-256 / SHA-256) JWS signing for ACME (RFC 8555) requests, plus the
 * JWK thumbprint (RFC 7638) needed for the HTTP-01 key authorization — pure JDK
 * {@code java.security}, no JOSE library (ADR-0019). ACME's JWS profile (RFC
 * 8555 §6.2) is narrower than general JWS: always the flattened JSON
 * serialization, always exactly one signature, protected header only.
 */
final class Jws {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Jws() {}

    static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    /** RFC 7638 canonical JWK for an EC P-256 public key: exactly the members
     *  {@code crv, kty, x, y}, lexicographically ordered (already alphabetical
     *  here — nothing to sort), no whitespace, fixed 32-byte big-endian
     *  coordinates. Order matters for the thumbprint hash, not just presentation. */
    static Map<String, String> jwk(ECPublicKey pub) {
        Map<String, String> jwk = new LinkedHashMap<>();
        jwk.put("crv", "P-256");
        jwk.put("kty", "EC");
        jwk.put("x", B64URL.encodeToString(fixedLength(pub.getW().getAffineX(), 32)));
        jwk.put("y", B64URL.encodeToString(fixedLength(pub.getW().getAffineY(), 32)));
        return jwk;
    }

    static String thumbprint(ECPublicKey pub) throws Exception {
        byte[] canonicalJson = JSON.writeValueAsBytes(jwk(pub));
        return B64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(canonicalJson));
    }

    /**
     * Builds the ACME flattened-JSON JWS body. Exactly one of {@code kid}/{@code jwk}
     * must be set by the caller in {@code protectedExtra} (RFC 8555 §6.2: {@code jwk}
     * only for the very first request that creates the account, {@code kid} —
     * the account URL — for every request after).
     *
     * @param protectedExtra header fields beyond {@code alg}/{@code nonce}/{@code url}
     *                       (i.e. {@code kid} or {@code jwk}), in the order to serialize
     * @param payload        the request body object, or {@code null} for POST-as-GET
     *                       (serializes to an empty payload string, not {@code "null"})
     */
    static String sign(PrivateKey key, String nonce, String url,
                        Map<String, Object> protectedExtra, Object payload) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "ES256");
        header.putAll(protectedExtra);
        header.put("nonce", nonce);
        header.put("url", url);

        String protectedB64 = B64URL.encodeToString(JSON.writeValueAsBytes(header));
        String payloadB64 = payload == null ? "" : B64URL.encodeToString(JSON.writeValueAsBytes(payload));

        byte[] signingInput = (protectedB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII);
        byte[] rawSignature = signRaw(signingInput, key);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("protected", protectedB64);
        body.put("payload", payloadB64);
        body.put("signature", B64URL.encodeToString(rawSignature));
        return JSON.writeValueAsString(body);
    }

    /** JCA's {@code SHA256withECDSA} produces an ASN.1 DER {@code ECDSA-Sig-Value}
     *  ({@code SEQUENCE { INTEGER r, INTEGER s }}); JWS ES256 (RFC 7518 §3.4)
     *  requires the raw, fixed-width {@code r || s} concatenation instead —
     *  32 bytes each for P-256, big-endian, zero-padded. Converting this
     *  correctly is the one easy-to-get-wrong detail of hand-rolling ES256 JWS. */
    private static byte[] signRaw(byte[] data, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(data);
        byte[] der = signer.sign();
        BigInteger[] rs = derToRS(der);
        byte[] out = new byte[64];
        byte[] r = fixedLength(rs[0], 32);
        byte[] s = fixedLength(rs[1], 32);
        System.arraycopy(r, 0, out, 0, 32);
        System.arraycopy(s, 0, out, 32, 32);
        return out;
    }

    /** Parses {@code SEQUENCE { INTEGER r, INTEGER s }} without a general ASN.1
     *  parser — only the two fixed fields this specific structure ever has. */
    private static BigInteger[] derToRS(byte[] der) {
        int pos = 0;
        if (der[pos++] != 0x30) throw new IllegalArgumentException("not a DER SEQUENCE");
        pos = skipLength(der, pos);
        BigInteger r; BigInteger s;
        int[] next = {pos};
        r = readDerInteger(der, next);
        s = readDerInteger(der, next);
        return new BigInteger[]{r, s};
    }

    private static BigInteger readDerInteger(byte[] der, int[] posRef) {
        int pos = posRef[0];
        if (der[pos++] != 0x02) throw new IllegalArgumentException("not a DER INTEGER");
        int len = der[pos++] & 0xFF;
        if ((len & 0x80) != 0) {
            int lenBytes = len & 0x7F;
            len = 0;
            for (int i = 0; i < lenBytes; i++) len = (len << 8) | (der[pos++] & 0xFF);
        }
        byte[] value = new byte[len];
        System.arraycopy(der, pos, value, 0, len);
        posRef[0] = pos + len;
        return new BigInteger(1, value);
    }

    private static int skipLength(byte[] der, int pos) {
        int len = der[pos++] & 0xFF;
        if ((len & 0x80) != 0) pos += (len & 0x7F);
        return pos;
    }

    private static byte[] fixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        // BigInteger.toByteArray() is signed-minimal, so a value with its top bit
        // set gets an extra leading 0x00 byte to keep it positive — strip that
        // before (re)padding to the fixed unsigned width JWS/EC coordinates need.
        int off = (raw.length > length && raw[0] == 0) ? raw.length - length : 0;
        byte[] out = new byte[length];
        int copyLen = Math.min(length, raw.length - off);
        System.arraycopy(raw, raw.length - copyLen, out, length - copyLen, copyLen);
        return out;
    }
}
