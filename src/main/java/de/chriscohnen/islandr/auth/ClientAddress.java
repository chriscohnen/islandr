package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.peer.IpSubnet;
import de.chriscohnen.islandr.settings.SettingsService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

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
 * the operator named. That list lives in Settings (Admin Console → Security),
 * not in a properties file — a reverse proxy is an operational fact like TLS,
 * and an operator who gets it wrong should not need a service restart to fix
 * it. It is empty by default: an unproxied install cannot be fooled by a
 * header at all, and a proxied one only trusts what its own proxy says.
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

    @Inject SettingsService settings;

    /** Parsed form of the setting, re-parsed only when the raw value changes. */
    private volatile String parsedFrom;
    private volatile List<IpSubnet> trusted = List.of();

    public String of(HttpServerRequest request) {
        if (request == null) return "unknown";
        return resolve(socketPeer(request), request.getHeader(clientIpHeader()));
    }

    private String clientIpHeader() {
        return settings.get().effectiveClientIpHeader();
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
        String raw = settings.get().trustedProxies;
        String key = raw == null ? "" : raw;
        // The value is admin-editable at runtime, so the cache is keyed on it
        // rather than computed once — and it is only ever read here, on a
        // failed-login path, so re-parsing on a change costs nothing.
        if (key.equals(parsedFrom)) return trusted;
        List<IpSubnet> parsed = parse(raw);
        trusted = parsed;
        parsedFrom = key;
        return parsed;
    }

    /**
     * Parses the setting. Skips entries it cannot read rather than throwing:
     * the value is validated on save ({@link #validate}), and a request path
     * must not start failing over a configuration value — refusing to resolve
     * an address would take the login endpoint down, which is a worse outcome
     * than falling back to the socket peer.
     */
    private static List<IpSubnet> parse(String raw) {
        List<IpSubnet> parsed = new ArrayList<>();
        if (raw == null || raw.isBlank()) return parsed;
        for (String part : raw.split(",")) {
            String entry = normaliseEntry(part);
            if (entry == null) continue;
            try {
                parsed.add(IpSubnet.parse(entry));
            } catch (IllegalArgumentException ignored) {
                // Validated on save; a bad entry here is not worth a 500.
            }
        }
        return parsed;
    }

    /** A bare address is a /32 or /128 — operators write both. */
    private static String normaliseEntry(String part) {
        String s = part.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("/")) s = s + (s.contains(":") ? "/128" : "/32");
        return s;
    }

    /**
     * Validates a trusted-proxies value on save, naming the entry at fault.
     * Returns the normalised value to store, or null for "nobody".
     *
     * @throws IllegalArgumentException with a message meant for the admin
     */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String entry = normaliseEntry(part);
            if (entry == null) continue;
            try {
                IpSubnet.parse(entry);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "'" + part.trim() + "' is not an IP address or CIDR");
            }
            out.add(part.trim());
        }
        return out.isEmpty() ? null : String.join(",", out);
    }
}
