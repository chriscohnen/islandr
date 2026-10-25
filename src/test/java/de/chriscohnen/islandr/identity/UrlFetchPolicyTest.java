package de.chriscohnen.islandr.identity;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * oidc-avatar-ssrf-xss: which URLs the hub may fetch on behalf of a login.
 *
 * <p>The OIDC {@code picture} claim is whatever the identity provider says —
 * and on a self-run provider (Keycloak attributes and the like) the user
 * says. Fetched without a check, it lets that user make the hub request any
 * address it can reach: loopback, its own WireGuard subnet, the site networks
 * it routes into. The response then came back as the user's avatar.
 *
 * <p>Pure function, no DNS: the resolver is handed in, so "a name that
 * resolves privately" is a test case rather than a network condition.
 */
class UrlFetchPolicyTest {

    private static final HostResolver PUBLIC = host -> addrs("142.250.180.1");

    private static InetAddress[] addrs(String... ips) {
        InetAddress[] out = new InetAddress[ips.length];
        try {
            for (int i = 0; i < ips.length; i++) out[i] = InetAddress.getByName(ips[i]);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static HostResolver resolving(Map<String, String[]> table) {
        return host -> {
            String[] ips = table.get(host);
            if (ips == null) throw new UnknownHostException(host);
            return addrs(ips);
        };
    }

    @Test
    void aPublicHttpsUrlIsFetchable() {
        URI u = UrlFetchPolicy.requireFetchable("https://lh3.googleusercontent.com/a/photo", PUBLIC, List.of());
        assertThat(u.getHost()).isEqualTo("lh3.googleusercontent.com");
    }

    @Test
    void plainHttpIsRefused() {
        assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable("http://lh3.googleusercontent.com/a", PUBLIC, List.of()))
                .isInstanceOf(UrlFetchPolicy.Refused.class).hasMessageContaining("https");
    }

    @Test
    void otherSchemesAreRefused() {
        for (String url : List.of("file:///etc/passwd", "ftp://example.com/a", "gopher://example.com/")) {
            assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable(url, PUBLIC, List.of()))
                    .as(url).isInstanceOf(UrlFetchPolicy.Refused.class);
        }
    }

    @Test
    void privateAndLocalAddressLiteralsAreRefused() {
        for (String host : List.of("127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "[::1]", "[fd00::1]", "[fe80::1]", "[::ffff:10.0.0.1]")) {
            HostResolver literal = h -> new InetAddress[] { InetAddress.getByName(h) };
            assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable("https://" + host + "/x", literal, List.of()))
                    .as(host).isInstanceOf(UrlFetchPolicy.Refused.class);
        }
    }

    /** The claim names a host, not an address — the check is on what it resolves to. */
    @Test
    void aNameThatResolvesPrivatelyIsRefused() {
        HostResolver r = resolving(Map.of("intranet.example.com", new String[] { "10.20.0.50" }));
        assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable("https://intranet.example.com/", r, List.of()))
                .isInstanceOf(UrlFetchPolicy.Refused.class);
    }

    /** One private answer among public ones is enough: the client may pick it. */
    @Test
    void oneBadAddressAmongGoodOnesIsRefused() {
        HostResolver r = resolving(Map.of("mixed.example.com", new String[] { "142.250.180.1", "127.0.0.1" }));
        assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable("https://mixed.example.com/", r, List.of()))
                .isInstanceOf(UrlFetchPolicy.Refused.class);
    }

    /** A site network may be a public range the hub routes into. It is
     *  reachable from the hub and from nowhere else — exactly what must not
     *  be fetchable on a user's say-so. */
    @Test
    void anAddressInsideARoutedNetworkIsRefusedEvenIfPublic() {
        HostResolver r = resolving(Map.of("site.example.com", new String[] { "198.51.100.20" }));
        assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable("https://site.example.com/", r,
                List.of("198.51.100.0/24")))
                .isInstanceOf(UrlFetchPolicy.Refused.class);
    }

    @Test
    void anUnresolvableHostIsRefused() {
        assertThatThrownBy(() -> UrlFetchPolicy.requireFetchable("https://nowhere.invalid/", resolving(Map.of()), List.of()))
                .isInstanceOf(UrlFetchPolicy.Refused.class);
    }

    @Test
    void aMalformedRoutedCidrDoesNotOpenAnything() {
        URI u = UrlFetchPolicy.requireFetchable("https://lh3.googleusercontent.com/a", PUBLIC,
                List.of("not-a-cidr", ""));
        assertThat(u).isNotNull();
    }
}
