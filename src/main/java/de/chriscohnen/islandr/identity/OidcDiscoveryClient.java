package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.io.IOException;
import java.util.Map;

/**
 * Resolves a generic OIDC provider's endpoints from its issuer URL via the
 * standard discovery document ({@code {issuer}/.well-known/openid-configuration},
 * RFC 8414 / OIDC Discovery 1.0) — issue #69. Used only at admin config-save
 * time, never on the login hot path (see {@link OidcCustomProvider}'s class doc
 * for why that split matters).
 *
 * <p>Hand-rolled HTTP GET + JSON parse, no OIDC/discovery library — consistent
 * with this project's existing hand-rolled posture ({@link IdTokenVerifier}'s
 * manual RS256 verification, the ACME client). {@link HttpFetcher} is already
 * the shared, test-doubled HTTP seam used by the rest of identity/ACME.
 */
@ApplicationScoped
public class OidcDiscoveryClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject HttpFetcher http;

    public record Discovered(String issuer, String authorizationEndpoint, String tokenEndpoint,
                             String jwksUri, String userinfoEndpoint) {}

    /**
     * @throws BadRequestException on anything that would leave a broken/half
     *         configured provider enabled — non-https issuer, unreachable
     *         discovery endpoint, malformed JSON, a missing required field,
     *         or a returned {@code issuer} that doesn't match what the admin
     *         entered (defends against a typo'd issuer silently pointing at
     *         someone else's discovery document, or a redirect to an
     *         unexpected host).
     */
    public Discovered discover(String issuerUrl) {
        if (issuerUrl == null || issuerUrl.isBlank()) {
            throw new BadRequestException("issuer URL is required");
        }
        String trimmed = issuerUrl.trim();
        if (!trimmed.startsWith("https://")) {
            throw new BadRequestException("issuer URL must use https://");
        }
        String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        String discoveryUrl = base + "/.well-known/openid-configuration";

        JsonNode doc;
        try {
            HttpFetcher.Response r = http.get(discoveryUrl, Map.of("Accept", "application/json"));
            if (r.status() != 200) {
                throw new BadRequestException("discovery request failed: HTTP " + r.status() + " for " + discoveryUrl);
            }
            doc = JSON.readTree(r.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BadRequestException("discovery document at " + discoveryUrl + " is not valid JSON");
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BadRequestException("could not reach discovery endpoint " + discoveryUrl + ": " + ex.getMessage());
        }

        String issuer = text(doc, "issuer");
        String authorize = text(doc, "authorization_endpoint");
        String token = text(doc, "token_endpoint");
        String jwks = text(doc, "jwks_uri");
        String userinfo = text(doc, "userinfo_endpoint");

        if (issuer == null || authorize == null || token == null || jwks == null) {
            throw new BadRequestException("discovery document at " + discoveryUrl
                    + " is missing a required field (issuer/authorization_endpoint/token_endpoint/jwks_uri)");
        }
        // Tolerate a trailing-slash mismatch (some IdPs' discovery 'issuer'
        // omits it even when the admin-entered URL has one, or vice versa) —
        // otherwise compare exactly. A mismatch beyond that is treated as
        // misconfiguration, not silently accepted.
        String normalizedIssuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        if (!normalizedIssuer.equals(base)) {
            throw new BadRequestException("discovery document's issuer (" + issuer
                    + ") does not match the configured issuer URL (" + issuerUrl + ")");
        }

        return new Discovered(issuer, authorize, token, jwks, userinfo);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
