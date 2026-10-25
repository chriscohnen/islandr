package de.chriscohnen.islandr.apikey;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
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

    /** @param scopes must be non-empty and every entry must be in
     *         {@link ApiKeyScope#CATALOG} — a key with no scope at all
     *         could authenticate but could never pass a single
     *         {@code Auth.requireScope} check, which is a confusing way to
     *         fail rather than a useful default.
     *  @param edgeHubId required exactly when {@code scopes} intersects
     *         {@link ApiKeyScope#EDGE_BOUND_SCOPES} (edge-psk-exposure) —
     *         applied to every such scope in the set, never to the others.
     *         A single key cannot yet hold two edge-bound scopes for two
     *         different edges; not a real-world need until an edge holds
     *         both {@code psks:read} and a second edge-bound scope for
     *         itself, which is exactly the one-edge-per-key case this
     *         already covers. */
    public CreateResult create(String label, Set<String> scopes, String actor) {
        return create(label, scopes, null, actor);
    }

    @Transactional
    public CreateResult create(String label, Set<String> scopes, String edgeHubId, String actor) {
        if (scopes == null || scopes.isEmpty()) {
            throw new BadRequestException("at least one scope is required");
        }
        for (String s : scopes) {
            if (!ApiKeyScope.CATALOG.contains(s)) {
                throw new BadRequestException("unknown scope: " + s);
            }
        }
        boolean needsEdge = scopes.stream().anyMatch(ApiKeyScope.EDGE_BOUND_SCOPES::contains);
        if (needsEdge) {
            if (edgeHubId == null || edgeHubId.isBlank()) {
                throw new BadRequestException("edgeHubId is required for scope(s): "
                        + scopes.stream().filter(ApiKeyScope.EDGE_BOUND_SCOPES::contains).toList());
            }
            if (de.chriscohnen.islandr.acl.EdgeHub.findById(edgeHubId) == null) {
                throw new NotFoundException("edge hub not found: " + edgeHubId);
            }
        }

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
        for (String s : scopes) {
            String boundEdge = ApiKeyScope.EDGE_BOUND_SCOPES.contains(s) ? edgeHubId : null;
            ApiKeyScope.createNew(k.id, s, boundEdge).persist();
        }
        return new CreateResult(k, raw);
    }

    public List<String> scopesOf(String apiKeyId) {
        return ApiKeyScope.scopesOf(apiKeyId);
    }

    /** Distinct edge hub ids this key's edge-bound scopes apply to — empty
     *  for a key with none. Used by {@link ApiKeyAuthFilter} to populate
     *  {@code AuthContext.edgeHubIds}, which {@code Auth.requireEdgeScope}
     *  checks against. */
    public Set<String> edgeHubIdsOf(String apiKeyId) {
        return ApiKeyScope.<ApiKeyScope>find("apiKeyId = ?1 and edgeHubId is not null", apiKeyId)
                .stream().map(s -> s.edgeHubId).collect(java.util.stream.Collectors.toSet());
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
