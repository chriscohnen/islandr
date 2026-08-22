package de.chriscohnen.islandr.network;

/** Raised when a diagnostics tool is missing or its invocation fails. */
public class NetworkDiagnosticsException extends RuntimeException {

    public NetworkDiagnosticsException(String message) {
        super(message);
    }

    public NetworkDiagnosticsException(String message, Throwable cause) {
        super(message, cause);
    }
}
