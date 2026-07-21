package de.chriscohnen.islandr.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwsTest {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void jwk_hasExactlyFourMembersInRfc7638CanonicalOrder() throws Exception {
        KeyPair pair = Jws.generateEcKeyPair();
        Map<String, String> jwk = Jws.jwk((ECPublicKey) pair.getPublic());

        assertThat(jwk.keySet()).containsExactly("crv", "kty", "x", "y"); // order matters for the thumbprint
        assertThat(jwk.get("crv")).isEqualTo("P-256");
        assertThat(jwk.get("kty")).isEqualTo("EC");
    }

    @Test
    void jwk_coordinatesAreExactly32BytesEvenWithLeadingZeros() throws Exception {
        // Run several keys — coordinates with a leading zero byte are common enough
        // (~1-in-256 per coordinate) that a few iterations should hit one, exercising
        // the fixed-length padding path, not just the common no-padding-needed case.
        for (int i = 0; i < 50; i++) {
            KeyPair pair = Jws.generateEcKeyPair();
            Map<String, String> jwk = Jws.jwk((ECPublicKey) pair.getPublic());
            assertThat(B64URL.decode(jwk.get("x"))).hasSize(32);
            assertThat(B64URL.decode(jwk.get("y"))).hasSize(32);
        }
    }

    @Test
    void thumbprint_isDeterministicForTheSameKey() throws Exception {
        KeyPair pair = Jws.generateEcKeyPair();
        String t1 = Jws.thumbprint((ECPublicKey) pair.getPublic());
        String t2 = Jws.thumbprint((ECPublicKey) pair.getPublic());
        assertThat(t1).isEqualTo(t2);
    }

    @Test
    void thumbprint_differsForDifferentKeys() throws Exception {
        KeyPair a = Jws.generateEcKeyPair();
        KeyPair b = Jws.generateEcKeyPair();
        assertThat(Jws.thumbprint((ECPublicKey) a.getPublic()))
                .isNotEqualTo(Jws.thumbprint((ECPublicKey) b.getPublic()));
    }

    @Test
    void thumbprint_is43CharsBase64UrlOfA32ByteSha256Digest() throws Exception {
        KeyPair pair = Jws.generateEcKeyPair();
        String thumbprint = Jws.thumbprint((ECPublicKey) pair.getPublic());
        assertThat(thumbprint).hasSize(43); // ceil(32*4/3) without '=' padding
        assertThat(B64URL.decode(thumbprint)).hasSize(32);
    }

    @Test
    void sign_producesAValidFlattenedJwsVerifiableAgainstThePublicKey() throws Exception {
        KeyPair pair = Jws.generateEcKeyPair();
        Map<String, Object> protectedExtra = new LinkedHashMap<>();
        protectedExtra.put("jwk", Jws.jwk((ECPublicKey) pair.getPublic()));

        String jws = Jws.sign(pair.getPrivate(), "test-nonce-1", "https://example.test/acme/order",
                protectedExtra, Map.of("hello", "world"));

        Map<?, ?> body = JSON.readValue(jws, Map.class);
        String protectedB64 = (String) body.get("protected");
        String payloadB64 = (String) body.get("payload");
        String signatureB64 = (String) body.get("signature");

        Map<?, ?> header = JSON.readValue(B64URL.decode(protectedB64), Map.class);
        assertThat(header.get("alg")).isEqualTo("ES256");
        assertThat(header.get("nonce")).isEqualTo("test-nonce-1");
        assertThat(header.get("url")).isEqualTo("https://example.test/acme/order");
        assertThat(((Map<?, ?>) header.get("jwk")).get("kty")).isEqualTo("EC");

        Map<?, ?> payload = JSON.readValue(B64URL.decode(payloadB64), Map.class);
        assertThat(payload.get("hello")).isEqualTo("world");

        // The one easy-to-get-wrong detail of hand-rolled ES256 JWS: the signature
        // must be raw r||s (64 bytes for P-256), not the JCA-native DER encoding —
        // verify by converting it back to DER ourselves (independent of the
        // production DER-to-raw conversion) and checking it verifies normally.
        byte[] rawSignature = B64URL.decode(signatureB64);
        assertThat(rawSignature).hasSize(64);
        byte[] derSignature = rawToDer(rawSignature);

        byte[] signingInput = (protectedB64 + "." + payloadB64).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(signingInput);
        assertThat(verifier.verify(derSignature)).isTrue();
    }

    @Test
    void sign_emptyPayloadForPostAsGet_encodesAsEmptyStringNotNullLiteral() throws Exception {
        KeyPair pair = Jws.generateEcKeyPair();
        String jws = Jws.sign(pair.getPrivate(), "n", "https://example.test/x",
                new LinkedHashMap<>(Map.of("kid", "https://example.test/acct/1")), null);

        Map<?, ?> body = JSON.readValue(jws, Map.class);
        assertThat(body.get("payload")).isEqualTo("");
    }

    @Test
    void sign_kidAndJwkAreMutuallyExclusiveByCallerChoice() throws Exception {
        // Not something Jws itself enforces (AcmeClient/AcmeService own that
        // RFC 8555 §6.2 rule) — this just documents that whichever the caller
        // puts in protectedExtra is exactly what ends up in the header, verbatim.
        KeyPair pair = Jws.generateEcKeyPair();
        String jws = Jws.sign(pair.getPrivate(), "n", "https://example.test/x",
                new LinkedHashMap<>(Map.of("kid", "https://example.test/acct/1")), null);
        Map<String, Object> header = JSON.readValue(
                B64URL.decode((String) JSON.readValue(jws, Map.class).get("protected")), Map.class);
        assertThat(header).doesNotContainKey("jwk");
        assertThat(header.get("kid")).isEqualTo("https://example.test/acct/1");
    }

    /** Test-local inverse of the production raw-to-DER conversion — deliberately
     *  independent code, so this test isn't just checking the encoder against itself. */
    private static byte[] rawToDer(byte[] raw) {
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64));
        byte[] rEnc = derInteger(r);
        byte[] sEnc = derInteger(s);
        byte[] seqContent = concat(rEnc, sEnc);
        return concat(new byte[]{0x30, (byte) seqContent.length}, seqContent);
    }

    private static byte[] derInteger(BigInteger v) {
        byte[] bytes = v.toByteArray(); // already minimal two's-complement-safe form
        return concat(new byte[]{0x02, (byte) bytes.length}, bytes);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
