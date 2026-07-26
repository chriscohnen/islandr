package de.chriscohnen.islandr.identity;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-scope replacement for {@link JdkHttpFetcher}. Use {@link #stub} to wire
 * a (url, response) entry; {@link #postFormStub} for token-exchange URLs.
 * Records every call in {@link #calls} so tests can assert on the wire.
 */
@Mock
@ApplicationScoped
public class FakeHttpFetcher implements HttpFetcher {

    public record Call(String method, String url, Map<String, String> form, Map<String, String> headers, byte[] rawBody) {
        public Call(String method, String url, Map<String, String> form, Map<String, String> headers) {
            this(method, url, form, headers, null);
        }
        /** UTF-8 view of {@link #rawBody} — {@code postBody} calls only (null otherwise). */
        public String rawBodyText() {
            return rawBody == null ? null : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // Static so tests can read recorded calls even if CDI hands out a different
    // instance than the one the production beans inject (Quarkus' @Mock + per-test
    // proxying has bitten us once already).
    private static final Map<String, Response> getStubs = new HashMap<>();
    private static final Map<String, Response> postStubs = new HashMap<>();
    private static final Map<String, Response> postBodyStubs = new HashMap<>();
    private static final Map<String, Response> deleteStubs = new HashMap<>();
    public static final List<Call> calls = new ArrayList<>();

    public void reset() {
        getStubs.clear();
        postStubs.clear();
        postBodyStubs.clear();
        deleteStubs.clear();
        calls.clear();
    }

    public void stub(String url, int status, byte[] body, Map<String, String> headers) {
        getStubs.put(url, new Response(status, body, headers == null ? Map.of() : headers));
    }

    public void stubJson(String url, int status, String json) {
        stub(url, status, json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("content-type", "application/json"));
    }

    public void postFormStub(String url, int status, String body, Map<String, String> headers) {
        postStubs.put(url, new Response(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headers == null ? Map.of("content-type", "application/json") : headers));
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws IOException {
        calls.add(new Call("GET", url, null, headers == null ? Map.of() : headers));
        Response r = getStubs.get(url);
        if (r == null) return new Response(404, new byte[0], Map.of());
        return r;
    }

    @Override
    public Response postForm(String url, Map<String, String> formFields, Map<String, String> headers) {
        calls.add(new Call("POST", url, formFields, headers == null ? Map.of() : headers));
        Response r = postStubs.get(url);
        if (r == null) return new Response(404, new byte[0], Map.of());
        return r;
    }

    public void postBodyStub(String url, int status, String body, Map<String, String> headers) {
        postBodyStubs.put(url, new Response(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headers == null ? Map.of("content-type", "application/json") : headers));
    }

    @Override
    public Response postBody(String url, byte[] body, String contentType, Map<String, String> headers) {
        calls.add(new Call("POST", url, null, headers == null ? Map.of() : headers, body));
        Response r = postBodyStubs.get(url);
        if (r == null) return new Response(404, new byte[0], Map.of());
        return r;
    }

    public void deleteStub(String url, int status, String body, Map<String, String> headers) {
        deleteStubs.put(url, new Response(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headers == null ? Map.of("content-type", "application/json") : headers));
    }

    @Override
    public Response delete(String url, Map<String, String> headers) {
        calls.add(new Call("DELETE", url, null, headers == null ? Map.of() : headers));
        Response r = deleteStubs.get(url);
        if (r == null) return new Response(404, new byte[0], Map.of());
        return r;
    }
}
