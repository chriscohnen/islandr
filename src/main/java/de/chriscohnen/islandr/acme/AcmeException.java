package de.chriscohnen.islandr.acme;

/**
 * Thrown for any ACME protocol failure — an unexpected HTTP status, a
 * malformed response, an authorization that goes {@code invalid}, or a
 * challenge/order that never reaches {@code valid} within the poll budget.
 * Unchecked, matching {@code WgException}/{@code NftablesException} — the
 * caller (the renewal scheduler, or the Settings "enable ACME" endpoint)
 * decides how to surface it, not this layer.
 */
public class AcmeException extends RuntimeException {

    public AcmeException(String message) {
        super(message);
    }

    public AcmeException(String message, Throwable cause) {
        super(message, cause);
    }
}
