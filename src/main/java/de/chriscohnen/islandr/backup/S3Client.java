package de.chriscohnen.islandr.backup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three S3 calls a backup needs — put, get, list — over {@code java.net.http}
 * (backup-s3). <b>There is deliberately no delete.</b> The access key an admin
 * gives Islandr should not be allowed to delete either (see the install docs),
 * so that a hub that is taken over cannot erase its own backups; keeping this
 * class free of a delete call keeps the code honest about that.
 *
 * <p>Works with anything that speaks the protocol: path-style addressing by
 * default (the form R2, B2 and MinIO all accept), virtual-host style on request.
 */
final class S3Client {

    /** Where and as whom. {@code endpoint} is a base URL such as {@code https://s3.eu-central-1.amazonaws.com}. */
    record Conn(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {}

    record Entry(String key, long size, Instant lastModified) {}

    /** The bucket said no (wrong key, no permission, no such bucket) — {@code status} is the HTTP status. */
    static final class S3Exception extends RuntimeException {
        final int status;
        S3Exception(int status, String message) { super(message); this.status = status; }
    }

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern CONTENTS = Pattern.compile("<Contents>(.*?)</Contents>", Pattern.DOTALL);
    private static final int MAX_PAGES = 20;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Conn conn;

    S3Client(Conn conn) {
        this.conn = conn;
    }

    void put(String key, byte[] body) {
        send("PUT", key, Map.of(), body);
    }

    byte[] get(String key) {
        return send("GET", key, Map.of(), null);
    }

    /** All objects under {@code prefix}, newest first. */
    List<Entry> list(String prefix) {
        List<Entry> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("list-type", "2");
            q.put("prefix", prefix);
            if (token != null) q.put("continuation-token", token);
            String xml = new String(send("GET", "", q, null), StandardCharsets.UTF_8);
            Matcher m = CONTENTS.matcher(xml);
            while (m.find()) {
                String block = m.group(1);
                String key = unescape(tag(block, "Key"));
                String size = tag(block, "Size");
                String when = tag(block, "LastModified");
                if (key == null) continue;
                out.add(new Entry(key, size == null ? 0 : Long.parseLong(size.trim()),
                        when == null ? null : Instant.parse(when.trim())));
            }
            if (!"true".equalsIgnoreCase(String.valueOf(tag(xml, "IsTruncated")).trim())) break;
            token = unescape(tag(xml, "NextContinuationToken"));
            if (token == null) break;
        }
        out.sort((a, b) -> {
            if (a.lastModified() == null || b.lastModified() == null) return b.key().compareTo(a.key());
            return b.lastModified().compareTo(a.lastModified());
        });
        return out;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s == null ? null : s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private byte[] send(String method, String key, Map<String, String> query, byte[] body) {
        URI base = URI.create(conn.endpoint().replaceAll("/+$", ""));
        String hostHeader = base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "");
        String path;
        if (conn.pathStyle()) {
            path = "/" + conn.bucket() + (key.isEmpty() ? "" : "/" + key);
        } else {
            hostHeader = conn.bucket() + "." + hostHeader;
            path = "/" + key;
        }
        String canonicalUri = S3Signer.encode(path, true);
        String canonicalQuery = S3Signer.canonicalQuery(query);
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = S3Signer.sha256Hex(payload);
        String amzDate = AMZ_DATE.format(Instant.now());

        Map<String, String> headers = new TreeMap<>();
        headers.put("host", hostHeader);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String auth = S3Signer.authorization(conn.accessKey(), conn.secretKey(), conn.region(),
                method, canonicalUri, canonicalQuery, headers, payloadHash, amzDate);

        String host = conn.pathStyle() ? base.getHost() : conn.bucket() + "." + base.getHost();
        String url = base.getScheme() + "://" + host + (base.getPort() > 0 ? ":" + base.getPort() : "")
                + canonicalUri + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", auth);
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                throw new S3Exception(res.statusCode(), describe(res.statusCode(), res.body()));
            }
            return res.body();
        } catch (IOException e) {
            throw new S3Exception(0, "could not reach " + conn.endpoint() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new S3Exception(0, "interrupted");
        }
    }

    /** The bucket's own words for what went wrong, without ever echoing a credential. */
    private static String describe(int status, byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        String code = tag(xml, "Code");
        String message = tag(xml, "Message");
        return "HTTP " + status + (code == null ? "" : " " + code) + (message == null ? "" : ": " + message);
    }
}
