package de.chriscohnen.islandr.proxy;

/**
 * Typed signal that the host-side {@code islandr-proxy} is unreachable — the
 * socket is absent, the connection is refused, or a request times out.
 *
 * <p>Deliberately distinct from operational failures
 * ({@link de.chriscohnen.islandr.wg.WgException},
 * {@link de.chriscohnen.islandr.firewall.NftablesException}): the enforcing
 * call-sites catch this specifically to enter the "enforcement unavailable"
 * degraded mode (persist config, enforce nothing, never fake success) instead
 * of failing the request. See ADR-0012 and the socket-proxy JVM design.
 */
public class ProxyUnavailableException extends RuntimeException {

    public ProxyUnavailableException(String message) {
        super(message);
    }

    public ProxyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
