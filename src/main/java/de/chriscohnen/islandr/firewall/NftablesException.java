package de.chriscohnen.islandr.firewall;

/** Thrown when {@code nft -f} fails despite a passing pre-validation. */
public class NftablesException extends RuntimeException {
    public NftablesException(String message) { super(message); }
    public NftablesException(String message, Throwable cause) { super(message, cause); }
}
