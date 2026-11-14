package de.chriscohnen.islandr.web;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The response headers every answer carries, static files included (a REST
 * response filter would miss them): no framing, no MIME sniffing, no referrer
 * leaving the origin, no camera, microphone or location.
 *
 * <p>Registered as a Vert.x route filter, so it must not touch the database:
 * it runs on the event loop. {@code Strict-Transport-Security} needs settings
 * and is therefore set in {@link HstsResponseFilter}, which runs on a worker.
 *
 * <p>Also holds one bit of state for that filter: whether the TLS material the
 * server itself serves is one a browser can be held to ({@link #markDirectTls}).
 */
@ApplicationScoped
public class SecurityHeaders {

    private volatile boolean directTlsTrusted;

    /** Computed once from the served index.html; see {@link ContentSecurityPolicy}. */
    private final String cspReportOnly = ContentSecurityPolicy.build(loadIndexHtml());

    String cspReportOnly() {
        return cspReportOnly;
    }

    private static String loadIndexHtml() {
        try (var in = SecurityHeaders.class.getResourceAsStream("/META-INF/resources/index.html")) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    void register(@Observes Filters filters) {
        filters.register(rc -> {
            rc.addHeadersEndHandler(v -> {
                var h = rc.response().headers();
                putIfAbsent(h, "X-Frame-Options", "DENY");
                putIfAbsent(h, "X-Content-Type-Options", "nosniff");
                putIfAbsent(h, "Referrer-Policy", "same-origin");
                putIfAbsent(h, "Content-Security-Policy-Report-Only", cspReportOnly);
                putIfAbsent(h, "Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            });
            rc.next();
        }, 100);
    }

    private static void putIfAbsent(io.vertx.core.MultiMap headers, String name, String value) {
        if (!headers.contains(name)) headers.add(name, value);
    }

    /** Called when the served certificate is (re)loaded: {@code true} only for
     *  material the operator supplied and a browser can validate. With the
     *  placeholder or a self-signed certificate HSTS would lock the operator
     *  out for good, because a browser offers no click-through under HSTS. */
    public void markDirectTls(boolean trusted) {
        this.directTlsTrusted = trusted;
    }

    public boolean directTlsTrusted() {
        return directTlsTrusted;
    }
}
