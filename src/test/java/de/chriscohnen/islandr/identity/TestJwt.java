package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RSA keypair + ID-Token builder + JWKS document builder. Lets tests assemble
 * a fully-signed ID-Token + matching JWKS without standing up a real IdP.
 */
public final class TestJwt {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder URL_NOPAD = Base64.getUrlEncoder().withoutPadding();

    public final KeyPair keyPair;
    public final String kid;

    public TestJwt(String kid) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            this.keyPair = g.generateKeyPair();
            this.kid = kid;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String jwksJson() {
        try {
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("kty", "RSA");
            key.put("kid", kid);
            key.put("alg", "RS256");
            key.put("use", "sig");
            key.put("n", URL_NOPAD.encodeToString(toUnsigned(pub.getModulus())));
            key.put("e", URL_NOPAD.encodeToString(toUnsigned(pub.getPublicExponent())));
            return JSON.writeValueAsString(Map.of("keys", new Object[] { key }));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String signIdToken(Map<String, Object> claims) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            header.put("kid", kid);
            String h = URL_NOPAD.encodeToString(JSON.writeValueAsBytes(header));
            String p = URL_NOPAD.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = h + "." + p;
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign((RSAPrivateKey) keyPair.getPrivate());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String sig = URL_NOPAD.encodeToString(s.sign());
            return signingInput + "." + sig;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Build a standard MS ID-Token payload for the given tenant + client. */
    public static Map<String, Object> microsoftClaims(String tenantId, String clientId,
                                                     String subject, String email, String name) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0");
        c.put("aud", clientId);
        c.put("sub", subject);
        c.put("email", email);
        c.put("name", name);
        c.put("iat", now);
        c.put("exp", now + 3600);
        return c;
    }

    public static Map<String, Object> googleClaims(String clientId, String subject,
                                                   String email, String name, String picture) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://accounts.google.com");
        c.put("aud", clientId);
        c.put("sub", subject);
        c.put("email", email);
        c.put("name", name);
        if (picture != null) c.put("picture", picture);
        c.put("iat", now);
        c.put("exp", now + 3600);
        return c;
    }

    /** Strip any leading sign byte so the JWK n/e are unsigned per RFC 7518. */
    private static byte[] toUnsigned(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] out = new byte[b.length - 1];
            System.arraycopy(b, 1, out, 0, out.length);
            return out;
        }
        return b;
    }
}
