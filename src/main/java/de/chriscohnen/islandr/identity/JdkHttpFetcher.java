package de.chriscohnen.islandr.identity;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Default
public class JdkHttpFetcher implements HttpFetcher {

    private HttpClient client;

    @PostConstruct
    void init() {
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (headers != null) headers.forEach(b::header);
        HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(resp.statusCode(), resp.body(), flattenHeaders(resp));
    }

    @Override
    public Response postForm(String url, Map<String, String> formFields, Map<String, String> headers)
            throws IOException, InterruptedException {
        String body = encodeForm(formFields);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers != null) headers.forEach(b::header);
        HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(resp.statusCode(), resp.body(), flattenHeaders(resp));
    }

    @Override
    public Response postBody(String url, byte[] body, String contentType, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers != null) headers.forEach(b::header);
        HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(resp.statusCode(), resp.body(), flattenHeaders(resp));
    }

    private static String encodeForm(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static Map<String, String> flattenHeaders(HttpResponse<?> resp) {
        Map<String, String> out = new LinkedHashMap<>();
        resp.headers().map().forEach((k, vs) -> {
            if (!vs.isEmpty()) out.put(k.toLowerCase(java.util.Locale.ROOT), vs.get(0));
        });
        return out;
    }
}
