package de.chriscohnen.islandr.backup;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * backup-s3 against a fake bucket (the JDK's own HTTP server): the client signs,
 * puts, lists and gets — and never sends a DELETE, which is the property the
 * "no delete permission" advice to admins rests on.
 */
class S3ClientTest {

    private HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath();
            requests.add(method + " " + path);
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] out = new byte[0];
            int status = 200;
            if (method.equals("PUT")) {
                objects.put(path, ex.getRequestBody().readAllBytes());
            } else if (path.equals("/bucket") || path.equals("/bucket/")) {
                StringBuilder xml = new StringBuilder("<ListBucketResult><IsTruncated>false</IsTruncated>");
                objects.forEach((k, v) -> xml.append("<Contents><Key>").append(k.substring("/bucket/".length()))
                        .append("</Key><Size>").append(v.length).append("</Size><LastModified>")
                        .append(k.contains("old") ? "2026-01-01T00:00:00.000Z" : "2026-11-01T00:00:00.000Z")
                        .append("</LastModified></Contents>"));
                out = xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);
            } else if (objects.containsKey(path)) {
                out = objects.get(path);
            } else {
                status = 403;
                out = "<Error><Code>AccessDenied</Code><Message>nope</Message></Error>".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private S3Client client() {
        return new S3Client(new S3Client.Conn("http://127.0.0.1:" + port, "auto", "bucket", "AKID", "SECRET", true));
    }

    @Test
    void putThenGetRoundTrips_andEveryRequestIsSigned() {
        S3Client c = client();
        c.put("islandr-backups/a b.islbak", new byte[]{1, 2, 3});
        assertThat(c.get("islandr-backups/a b.islbak")).containsExactly(1, 2, 3);
        assertThat(authHeaders).allMatch(h -> h.startsWith("AWS4-HMAC-SHA256 Credential=AKID/"));
        assertThat(requests.get(0)).isEqualTo("PUT /bucket/islandr-backups/a%20b.islbak");
    }

    @Test
    void listReturnsNewestFirst_withSizes() {
        S3Client c = client();
        c.put("p/old.islbak", new byte[10]);
        c.put("p/new.islbak", new byte[20]);
        List<S3Client.Entry> list = c.list("p/");
        assertThat(list).extracting(S3Client.Entry::key).containsExactly("p/new.islbak", "p/old.islbak");
        assertThat(list.get(0).size()).isEqualTo(20);
    }

    @Test
    void aRefusalCarriesTheBucketsOwnWords_andNoCredential() {
        assertThatThrownBy(() -> client().get("missing"))
                .isInstanceOf(S3Client.S3Exception.class)
                .hasMessageContaining("403").hasMessageContaining("AccessDenied")
                .hasMessageNotContaining("SECRET");
    }

    @Test
    void neverSendsADelete() {
        S3Client c = client();
        c.put("x", new byte[]{1});
        c.list("");
        c.get("x");
        assertThat(requests).noneMatch(r -> r.startsWith("DELETE"));
    }
}
