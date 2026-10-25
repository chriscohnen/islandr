package de.chriscohnen.islandr.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * oidc-avatar-ssrf-xss, the fetch: {@link AvatarFetcher#fetchByUrl} only
 * requests what {@link UrlFetchPolicy} allows, checks every redirect hop the
 * same way, and keeps the type the bytes have — never the remote server's
 * {@code Content-Type}.
 */
class AvatarFetcherPictureUrlTest {

    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F' };
    private static final byte[] HTML = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);

    private final Map<String, HttpFetcher.Response> responses = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final Map<String, String> dns = new HashMap<>();
    private AvatarFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new AvatarFetcher();
        fetcher.http = new HttpFetcher() {
            @Override public Response get(String url, Map<String, String> h) {
                throw new AssertionError("must not follow redirects on its own: " + url);
            }
            @Override public Response getWithoutRedirects(String url, Map<String, String> h) {
                requested.add(url);
                Response r = responses.get(url);
                return r != null ? r : new Response(404, new byte[0], Map.of());
            }
            @Override public Response postForm(String u, Map<String, String> f, Map<String, String> h) { throw new UnsupportedOperationException(); }
            @Override public Response postBody(String u, byte[] b, String c, Map<String, String> h) { throw new UnsupportedOperationException(); }
            @Override public Response delete(String u, Map<String, String> h) { throw new UnsupportedOperationException(); }
        };
        fetcher.resolver = host -> {
            String ip = dns.getOrDefault(host, "142.250.180.1");
            if (ip.equals("NXDOMAIN")) throw new UnknownHostException(host);
            return new InetAddress[] { InetAddress.getByName(ip) };
        };
        fetcher.routed = new RoutedNetworks() {
            @Override public List<String> cidrs() { return List.of("10.8.0.0/24", "198.51.100.0/24"); }
        };
    }

    private void serve(String url, int status, byte[] body, String contentType) {
        responses.put(url, new HttpFetcher.Response(status, body, Map.of("content-type", contentType)));
    }

    @Test
    void aPublicJpegIsFetchedAndStoredAsJpeg() {
        serve("https://lh3.googleusercontent.com/a/p", 200, JPEG, "image/jpeg");
        AvatarFetcher.Avatar a = fetcher.fetchByUrl("https://lh3.googleusercontent.com/a/p");
        assertThat(a).isNotNull();
        assertThat(a.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void anHttpUrlIsNotRequestedAtAll() {
        assertThat(fetcher.fetchByUrl("http://lh3.googleusercontent.com/a/p")).isNull();
        assertThat(requested).isEmpty();
    }

    @Test
    void aPrivateAddressIsNotRequestedAtAll() {
        assertThat(fetcher.fetchByUrl("https://127.0.0.1:8443/api/v1/settings")).isNull();
        assertThat(fetcher.fetchByUrl("https://10.8.0.1/")).isNull();
        assertThat(requested).isEmpty();
    }

    @Test
    void aNameResolvingIntoARoutedSiteIsNotRequested() {
        dns.put("printer.example.com", "198.51.100.7");
        assertThat(fetcher.fetchByUrl("https://printer.example.com/status")).isNull();
        assertThat(requested).isEmpty();
    }

    /** The first hop is fine; the redirect points inside. Checked again, refused. */
    @Test
    void aRedirectToAPrivateAddressIsNotFollowed() {
        responses.put("https://evil.example.com/p", new HttpFetcher.Response(302, new byte[0],
                Map.of("location", "https://192.168.1.1/admin")));
        assertThat(fetcher.fetchByUrl("https://evil.example.com/p")).isNull();
        assertThat(requested).containsExactly("https://evil.example.com/p");
    }

    @Test
    void aRedirectToAnotherPublicHostIsFollowed() {
        responses.put("https://idp.example.com/avatar/1", new HttpFetcher.Response(302, new byte[0],
                Map.of("location", "https://cdn.example.com/a.jpg")));
        serve("https://cdn.example.com/a.jpg", 200, JPEG, "image/jpeg");
        assertThat(fetcher.fetchByUrl("https://idp.example.com/avatar/1")).isNotNull();
    }

    @Test
    void endlessRedirectsGiveUp() {
        responses.put("https://loop.example.com/a", new HttpFetcher.Response(302, new byte[0],
                Map.of("location", "https://loop.example.com/a")));
        assertThat(fetcher.fetchByUrl("https://loop.example.com/a")).isNull();
        assertThat(requested.size()).isLessThanOrEqualTo(4);
    }

    @Test
    void anHtmlAnswerIsNotStored() {
        serve("https://evil.example.com/p", 200, HTML, "text/html");
        assertThat(fetcher.fetchByUrl("https://evil.example.com/p")).isNull();
    }

    /** The server's claim does not count — the bytes do. */
    @Test
    void htmlClaimingToBeAnImageIsNotStored() {
        serve("https://evil.example.com/p", 200, HTML, "image/png");
        assertThat(fetcher.fetchByUrl("https://evil.example.com/p")).isNull();
    }

    @Test
    void aJpegServedWithAWrongTypeIsStoredAsJpeg() {
        serve("https://odd.example.com/p", 200, JPEG, "application/octet-stream");
        AvatarFetcher.Avatar a = fetcher.fetchByUrl("https://odd.example.com/p");
        assertThat(a).isNotNull();
        assertThat(a.contentType()).isEqualTo("image/jpeg");
    }
}
