package de.chriscohnen.islandr.apikey;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues, lists, authenticates, and revokes external-API keys (issue #15,
 * ADR-0026). {@link ApiKeyAuthFilter} calls {@link #authenticate} on every
 * request carrying a Bearer token; everything else here is admin-console
 * CRUD, session-authenticated like any other admin resource.
 */
@ApplicationScoped
public class ApiKeyService {

    /** Every raw key starts with this so a key is recognisable at a glance
     *  (in logs, in a pasted snippet) as an Islandr credential — same idea
     *  as GitHub's `ghp_`/Stripe's `sk_` prefixes. */
    private static final String KEY_PREFIX_LITERAL = "islandr_";

    private volatile SecureRandom rng;

    public List<ApiKey> listAll() {
        return ApiKey.<ApiKey>listAll();
    }

    public ApiKey get(String id) {
        ApiKey k = ApiKey.findById(id);
        if (k == null) throw new NotFoundException("unknown API key: " + id);
        return k;
    }

    public record CreateResult(ApiKey apiKey, String rawKey) {}

    @Transactional
    public CreateResult create(String label, String actor) {
        byte[] buf = new byte[32];
        rng().nextBytes(buf);
        String raw = KEY_PREFIX_LITERAL + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        ApiKey k = new ApiKey();
        k.id = UUID.randomUUID().toString();
        k.label = label;
        k.keyHash = sha256Hex(raw);
        // Prefix + a handful of the random chars — enough to tell keys apart
        // in a list, nowhere near enough to reconstruct or brute-force the key.
        k.keyPrefix = raw.substring(0, Math.min(raw.length(), KEY_PREFIX_LITERAL.length() + 6));
        k.createdAt = Instant.now();
        k.createdBy = actor;
        k.persist();
        return new CreateResult(k, raw);
    }

    /** Called by {@link ApiKeyAuthFilter} on every Bearer-authenticated
     *  request. Returns null on anything that doesn't validate — unknown
     *  hash, revoked key — never throws, so a bad/expired token degrades to
     *  "unauthenticated", not a 500. Bumps {@code lastUsedAt} on success so
     *  the admin console can show real usage, not just issuance date. */
    @Transactional
    public ApiKey authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String hash = sha256Hex(rawKey);
        ApiKey k = ApiKey.find("keyHash", hash).firstResult();
        if (k == null || !k.isActive()) return null;
        k.lastUsedAt = Instant.now();
        return k;
    }

    @Transactional
    public void revoke(String id, String actor) {
        ApiKey k = get(id);
        if (k.revokedAt == null) k.revokedAt = Instant.now();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private SecureRandom rng() {
        SecureRandom r = rng;
        if (r == null) {
            synchronized (this) {
                r = rng;
                if (r == null) {
                    r = new SecureRandom();
                    rng = r;
                }
            }
        }
        return r;
    }
}
