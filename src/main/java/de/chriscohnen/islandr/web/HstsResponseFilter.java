package de.chriscohnen.islandr.web;

import de.chriscohnen.islandr.auth.ClientAddress;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * {@code Strict-Transport-Security} on HTTPS answers only. Plain HTTP must not
 * carry it, and neither may an HTTPS listener serving the placeholder or a
 * self-signed certificate: once a browser has seen the header it refuses the
 * certificate warning, and the console is unreachable until the header expires.
 * A request that came through a proxy the operator named counts as HTTPS; the
 * proxy's certificate is the proxy's business.
 */
@Provider
public class HstsResponseFilter implements ContainerResponseFilter {

    static final String VALUE = "max-age=31536000";

    @Inject ClientAddress clientAddress;
    @Inject SecurityHeaders securityHeaders;
    @Inject io.quarkus.vertx.http.runtime.CurrentVertxRequest currentRequest;

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        if (res.getHeaders().containsKey("Strict-Transport-Security")) return;
        var current = currentRequest.getCurrent();
        if (current != null && applies(current.request())) res.getHeaders().putSingle("Strict-Transport-Security", VALUE);
    }

    boolean applies(HttpServerRequest r) {
        if (r == null) return false;
        var conn = r.connection();
        boolean directTls = conn != null && conn.isSsl();
        return applies(directTls, !directTls && clientAddress.isHttps(r));
    }

    /** The decision, free of Vert.x: a direct TLS socket counts only with a
     *  certificate a browser can validate; a trusted proxy's HTTPS always counts. */
    boolean applies(boolean directTls, boolean httpsViaTrustedProxy) {
        if (directTls) return securityHeaders.directTlsTrusted();
        return httpsViaTrustedProxy;
    }
}
