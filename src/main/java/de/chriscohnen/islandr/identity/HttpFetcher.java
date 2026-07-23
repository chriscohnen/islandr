package de.chriscohnen.islandr.identity;

import java.io.IOException;
import java.util.Map;

/**
 * Tiny HTTP abstraction so OIDC + Graph + Gravatar code — and ACME (ADR-0019) —
 * can be unit-tested with a fake without pulling in WireMock. Only the request
 * shapes actually used anywhere in the codebase are modelled.
 */
public interface HttpFetcher {

    record Response(int status, byte[] body, Map<String, String> headers) {
        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** GET, returns body and status. Follows redirects. */
    Response get(String url, Map<String, String> headers) throws IOException, InterruptedException;

    /** POST form-urlencoded body. */
    Response postForm(String url, Map<String, String> formFields, Map<String, String> headers)
            throws IOException, InterruptedException;

    /** POST an arbitrary raw body (e.g. ACME's {@code application/jose+json} JWS
     *  requests, ADR-0019) — the request shapes above don't fit a signed JSON body. */
    Response postBody(String url, byte[] body, String contentType, Map<String, String> headers)
            throws IOException, InterruptedException;
}
