package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-JWKS-URL cache of public keys (kid → PublicKey). On a cache miss for a
 * given kid we re-fetch (provider may have rotated). TTL is a safety net so
 * deleted keys eventually drop out.
 */
@ApplicationScoped
public class JwksCache {

    static final Duration TTL = Duration.ofHours(6);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject HttpFetcher http;

    private record Entry(Map<String, PublicKey> keysByKid, Instant fetchedAt) {}

    private final ConcurrentHashMap<String, Entry> byUrl = new ConcurrentHashMap<>();

    public PublicKey getKey(String jwksUrl, String kid) {
        Entry e = byUrl.get(jwksUrl);
        if (e == null || e.fetchedAt.plus(TTL).isBefore(Instant.now()) || !e.keysByKid.containsKey(kid)) {
            e = refresh(jwksUrl);
        }
        PublicKey k = e.keysByKid.get(kid);
        if (k == null) {
            throw new IllegalStateException("kid '" + kid + "' not present in JWKS at " + jwksUrl);
        }
        return k;
    }

    private Entry refresh(String jwksUrl) {
        try {
            HttpFetcher.Response r = http.get(jwksUrl, null);
            if (r.status() != 200) {
                throw new IllegalStateException("JWKS fetch failed: HTTP " + r.status() + " from " + jwksUrl);
            }
            JsonNode root = JSON.readTree(r.body());
            JsonNode keys = root.get("keys");
            if (keys == null || !keys.isArray()) {
                throw new IllegalStateException("JWKS missing 'keys' array");
            }
            Map<String, PublicKey> out = new java.util.HashMap<>();
            for (JsonNode k : keys) {
                String kty = textOrNull(k, "kty");
                if (!"RSA".equals(kty)) continue;  // skip non-RSA keys (Google occasionally rotates EC in)
                String kid = textOrNull(k, "kid");
                String n = textOrNull(k, "n");
                String e = textOrNull(k, "e");
                if (kid == null || n == null || e == null) continue;
                out.put(kid, buildRsaKey(n, e));
            }
            Entry e = new Entry(Map.copyOf(out), Instant.now());
            byUrl.put(jwksUrl, e);
            return e;
        } catch (Exception ex) {
            throw new IllegalStateException("could not refresh JWKS from " + jwksUrl + ": " + ex.getMessage(), ex);
        }
    }

    static PublicKey buildRsaKey(String nB64Url, String eB64Url) {
        try {
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(nB64Url));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(eB64Url));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (Exception ex) {
            throw new IllegalStateException("could not build RSA key from JWKS entry", ex);
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
