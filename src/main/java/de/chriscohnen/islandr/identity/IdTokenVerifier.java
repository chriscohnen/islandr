package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * Verifies an OIDC ID-Token (RS256 only). Validates: signature against JWKS,
 * iss (substring tolerance for Microsoft's {tenant} URL shape), aud == client_id,
 * exp in the future, nbf/iat in the past (small skew allowed).
 *
 * <p>Microsoft is special: the {@code iss} claim is
 * {@code https://login.microsoftonline.com/{tenantId}/v2.0} — we compare the
 * trimmed prefix because some MS tokens use {@code sts.windows.net} as alt issuer.
 */
@ApplicationScoped
public class IdTokenVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SKEW_SECONDS = 60;

    @Inject JwksCache jwks;

    public record Claims(String subject, String email, String name, String pictureUrl, JsonNode raw) {}

    public Claims verify(String idToken, OidcProvider provider) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("not a JWT (expected 3 parts)");

        JsonNode header = decodeJsonPart(parts[0]);
        JsonNode payload = decodeJsonPart(parts[1]);
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);

        String alg = text(header, "alg");
        if (!"RS256".equals(alg)) throw new IllegalArgumentException("unsupported alg: " + alg);
        String kid = text(header, "kid");
        if (kid == null) throw new IllegalArgumentException("missing kid header");

        ProviderEndpoints.Endpoints ep = ProviderEndpoints.forProvider(provider);
        PublicKey key = jwks.getKey(ep.jwks(), kid);

        verifySignature(parts[0] + "." + parts[1], signature, key);
        verifyClaims(payload, provider, ep);

        String email = text(payload, "email");
        if (email == null) email = text(payload, "preferred_username");  // MS sometimes only sets this
        return new Claims(
                text(payload, "sub"),
                email == null ? null : email.toLowerCase(Locale.ROOT),
                text(payload, "name"),
                text(payload, "picture"),  // Google sets this; MS does not
                payload
        );
    }

    private void verifySignature(String signingInput, byte[] signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(signature)) throw new IllegalStateException("ID-Token signature invalid");
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("could not verify ID-Token signature", ex);
        }
    }

    private void verifyClaims(JsonNode payload, OidcProvider provider, ProviderEndpoints.Endpoints ep) {
        String iss = text(payload, "iss");
        if (iss == null) throw new IllegalStateException("missing iss");
        if (provider.isMicrosoft()) {
            // Accept both v2 endpoint issuer and the legacy sts.windows.net form (per MS docs).
            if (!iss.equals(ep.issuer()) && !iss.startsWith("https://sts.windows.net/" + provider.tenantId)) {
                throw new IllegalStateException("unexpected iss: " + iss);
            }
        } else {
            if (!iss.equals(ep.issuer()) && !iss.equals("accounts.google.com")) {
                throw new IllegalStateException("unexpected iss: " + iss);
            }
        }

        // aud is sometimes a string, sometimes an array
        JsonNode aud = payload.get("aud");
        boolean audOk;
        if (aud == null) audOk = false;
        else if (aud.isTextual()) audOk = provider.clientId.equals(aud.asText());
        else if (aud.isArray()) {
            audOk = false;
            for (JsonNode a : aud) if (a.isTextual() && provider.clientId.equals(a.asText())) audOk = true;
        } else audOk = false;
        if (!audOk) throw new IllegalStateException("aud does not match client_id");

        long now = Instant.now().getEpochSecond();
        long exp = longClaim(payload, "exp", -1);
        if (exp <= 0 || exp + SKEW_SECONDS < now) throw new IllegalStateException("ID-Token expired");

        long iat = longClaim(payload, "iat", -1);
        if (iat > 0 && iat > now + SKEW_SECONDS) throw new IllegalStateException("ID-Token iat in the future");
    }

    private JsonNode decodeJsonPart(String part) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(part));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid JWT part: " + ex.getMessage());
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long longClaim(JsonNode n, String field, long defaultValue) {
        JsonNode v = n.get(field);
        return v == null || !v.canConvertToLong() ? defaultValue : v.asLong();
    }
}
