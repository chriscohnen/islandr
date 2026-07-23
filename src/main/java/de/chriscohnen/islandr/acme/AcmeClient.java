package de.chriscohnen.islandr.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.identity.HttpFetcher;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Low-level RFC 8555 mechanics: directory discovery, nonce management, and
 * JWS-signed requests — everything protocol-generic, independent of the
 * higher-level issuance flow ({@link AcmeService}). One instance is used for
 * exactly one issuance attempt; nonces are stateful and not meant to be
 * shared across concurrent attempts.
 */
final class AcmeClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Directory(String newNonce, String newAccount, String newOrder, JsonNode raw) {}

    /** One ACME response: parsed JSON body, status, and the headers callers need
     *  (Location for account/order URLs, Replay-Nonce already consumed into
     *  {@link #nonce} before this is returned). */
    record AcmeResponse(int status, JsonNode body, Map<String, String> headers) {
        String location() { return headers.get("location"); }
    }

    private final HttpFetcher http;
    private final PrivateKey accountKey;
    private final Map<String, Object> keyIdentifier; // {"jwk": ...} until we have a kid, then {"kid": ...}
    private String nonce;

    AcmeClient(HttpFetcher http, PrivateKey accountKey, Map<String, Object> keyIdentifier) {
        this.http = http;
        this.accountKey = accountKey;
        this.keyIdentifier = keyIdentifier;
    }

    /** Switches from {@code jwk}-identified requests (only valid before the account
     *  exists) to {@code kid}-identified ones, once {@link AcmeService} has the
     *  account URL from the {@code newAccount} response's {@code Location} header. */
    void useAccountUrl(String accountUrl) {
        keyIdentifier.clear();
        keyIdentifier.put("kid", accountUrl);
    }

    Directory directory(String directoryUrl) {
        try {
            HttpFetcher.Response resp = http.get(directoryUrl, Map.of());
            if (resp.status() != 200) {
                throw new AcmeException("GET directory failed: HTTP " + resp.status() + " " + resp.text());
            }
            JsonNode root = JSON.readTree(resp.body());
            return new Directory(
                    text(root, "newNonce"), text(root, "newAccount"), text(root, "newOrder"), root);
        } catch (AcmeException e) {
            throw e;
        } catch (Exception e) {
            throw new AcmeException("could not fetch ACME directory at " + directoryUrl, e);
        }
    }

    void primeNonce(String newNonceUrl) {
        try {
            HttpFetcher.Response resp = http.get(newNonceUrl, Map.of());
            String fresh = resp.headers().get("replay-nonce");
            if (fresh == null) throw new AcmeException("newNonce response had no Replay-Nonce header");
            this.nonce = fresh;
        } catch (AcmeException e) {
            throw e;
        } catch (Exception e) {
            throw new AcmeException("could not fetch a fresh ACME nonce", e);
        }
    }

    /** POST-as-GET (RFC 8555 §6.3) — an empty-payload signed POST, used to fetch
     *  authorization/order state without a plain unsigned GET (ACME requires every
     *  read of account-linked resources to be authenticated this way). */
    AcmeResponse postAsGet(String url) {
        return post(url, null);
    }

    AcmeResponse post(String url, Object payload) {
        if (nonce == null) throw new AcmeException("no nonce available — call primeNonce first");
        try {
            String jws = Jws.sign(accountKey, nonce, url, new LinkedHashMap<>(keyIdentifier), payload);
            HttpFetcher.Response resp = http.postBody(
                    url, jws.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/jose+json", Map.of());
            String freshNonce = resp.headers().get("replay-nonce");
            if (freshNonce != null) this.nonce = freshNonce;

            JsonNode body = resp.body().length == 0 ? null : JSON.readTree(resp.body());
            if (resp.status() >= 400) {
                String problem = body != null ? body.toString() : resp.text();
                throw new AcmeException("ACME request to " + url + " failed: HTTP " + resp.status() + " " + problem);
            }
            return new AcmeResponse(resp.status(), body, resp.headers());
        } catch (AcmeException e) {
            throw e;
        } catch (Exception e) {
            throw new AcmeException("ACME request to " + url + " failed", e);
        }
    }

    /** Like {@link #postAsGet} but for the one response in the flow that isn't
     *  JSON — the finalized certificate download, {@code application/pem-certificate-chain}. */
    String postAsGetRaw(String url) {
        if (nonce == null) throw new AcmeException("no nonce available — call primeNonce first");
        try {
            String jws = Jws.sign(accountKey, nonce, url, new LinkedHashMap<>(keyIdentifier), null);
            HttpFetcher.Response resp = http.postBody(
                    url, jws.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/jose+json", Map.of());
            String freshNonce = resp.headers().get("replay-nonce");
            if (freshNonce != null) this.nonce = freshNonce;
            if (resp.status() >= 400) {
                throw new AcmeException("ACME certificate download from " + url + " failed: HTTP " + resp.status() + " " + resp.text());
            }
            return resp.text();
        } catch (AcmeException e) {
            throw e;
        } catch (Exception e) {
            throw new AcmeException("ACME certificate download from " + url + " failed", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) throw new AcmeException("ACME directory missing '" + field + "'");
        return v.asText();
    }
}
