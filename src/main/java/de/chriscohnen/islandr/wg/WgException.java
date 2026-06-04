package de.chriscohnen.islandr.wg;

/**
 * Thrown when a WireGuard CLI invocation fails (non-zero exit, timeout, I/O error)
 * or when output cannot be parsed. Unchecked so adapter callers don't need
 * boilerplate try/catch — the REST layer turns this into a 500 with a logged cause.
 */
public class WgException extends RuntimeException {

    public WgException(String message) {
        super(message);
    }

    public WgException(String message, Throwable cause) {
        super(message, cause);
    }
}
