package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.peer.IpSubnet;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the address a request really came from — the one a ban would have to
 * name (issue #80).
 *
 * <p>Two ways to get this wrong, and both are worse than not banning at all.
 * A hub behind Cloudflare or any reverse proxy sees the proxy on every request,
 * so banning the peer address bans the proxy — which is to say everyone,
 * including the operator, while the attacker keeps going. But trusting a
 * forwarded header instead turns the mechanism into a weapon: anyone can send
 * {@code X-Forwarded-For: 1.2.3.4}, evade their own ban by rotating it, and
 * get an arbitrary third party banned in their place.
 *
 * <p>So the header counts only when the request actually arrived from a proxy
 * the operator named. {@code islandr.auth.trusted-proxies} is empty by default:
 * an unproxied install cannot be fooled by a header at all, and a proxied one
 * only trusts what its own proxy says.
 *
 * <p>This deliberately reads the <em>socket</em> peer rather than Vert.x's
 * resolved {@code remoteAddress()}: the latter is already rewritten by
 * {@code quarkus.http.proxy.proxy-address-forwarding}, which Islandr keeps on
 * so OIDC redirect URIs carry the external scheme and host. Getting the right
 * URL and deciding who to ban are different questions with different levels of
 * trust; only the second one is settled here.
 */
@ApplicationScoped
public class ClientAddress {

    /** CIDRs whose requests may speak for someone else. Empty = nobody may. */
    @ConfigProperty(name = "islandr.auth.trusted-proxies")
    Optional<String> trustedProxies;

    /** Cloudflare users set this to CF-Connecting-IP. */
    @ConfigProperty(name = "islandr.auth.client-ip-header", defaultValue = "X-Forwarded-For")
    String clientIpHeader;

    private volatile List<IpSubnet> trusted;

    public String of(HttpServerRequest request) {
        if (request == null) return "unknown";
        return resolve(socketPeer(request), request.getHeader(clientIpHeader));
    }

    /**
     * The decision itself, free of Vert.x: the socket peer the connection
     * really came from, and whatever the forwarded header claimed.
     */
    String resolve(String socketPeer, String header) {
        String socket = normalise(socketPeer);
        if (socket == null) return "unknown";
        if (!isTrusted(socket)) return socket;
        if (header == null || header.isBlank()) return socket;

        // Walk right to left and take the first address the trusted chain did
        // not vouch for. Everything to its left was written by whoever sat
        // further out, which is to say by nobody we know.
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalise(hops[i]);
            if (hop == null) continue;
            if (!isTrusted(hop)) return hop;
        }
        return socket;
    }

    private static String socketPeer(HttpServerRequest request) {
        var conn = request.connection();
        if (conn == null || conn.remoteAddress() == null) return null;
        return normalise(conn.remoteAddress().hostAddress());
    }

    /** Strips whitespace, a port suffix, and IPv6 brackets. */
    static String normalise(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close > 1 ? s.substring(1, close) : null;
        }
        // "1.2.3.4:5678" — but not "fe80::1", which has several colons.
        int colon = s.indexOf(':');
        if (colon > 0 && s.indexOf(':', colon + 1) < 0) s = s.substring(0, colon);
        return s.isEmpty() ? null : s;
    }

    boolean isTrusted(String address) {
        for (IpSubnet net : trustedSubnets()) {
            try {
                if (net.contains(address)) return true;
            } catch (IllegalArgumentException ignored) {
                // Not an address we can compare — treat as untrusted.
            }
        }
        return false;
    }

    private List<IpSubnet> trustedSubnets() {
        List<IpSubnet> t = trusted;
        if (t != null) return t;
        List<IpSubnet> parsed = new ArrayList<>();
        String raw = trustedProxies == null ? null : trustedProxies.orElse(null);
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                // A bare address is a /32 or /128 — operators write both.
                if (!s.contains("/")) s = s + (s.contains(":") ? "/128" : "/32");
                try {
                    parsed.add(IpSubnet.parse(s));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "islandr.auth.trusted-proxies contains an invalid entry: " + part, e);
                }
            }
        }
        trusted = parsed;
        return parsed;
    }
}
