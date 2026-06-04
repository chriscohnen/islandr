package de.chriscohnen.islandr.identity;

import java.io.IOException;
import java.util.Map;

/**
 * Tiny HTTP abstraction so OIDC + Graph + Gravatar code can be unit-tested
 * with a fake without pulling in WireMock. Only the four request shapes we
 * actually use are modelled.
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
}
