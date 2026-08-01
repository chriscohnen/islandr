package de.chriscohnen.islandr.acme.dns;

/**
 * Creates and removes the {@code _acme-challenge} TXT record a DNS-01
 * challenge needs (ADR-0020) — a generic seam so a second provider is a new
 * implementation of this interface, not a rewrite of {@code AcmeService}'s
 * orchestration. Cloudflare ({@link CloudflareDnsProvider}) is the only
 * implementation in v1.
 */
public interface DnsProvider {

    /**
     * Creates (or replaces) the {@code _acme-challenge.<fqdn>} TXT record with
     * {@code value} (the base64url SHA-256 digest of the key authorization,
     * RFC 8555 §8.4 — already computed by the caller, this just publishes it).
     *
     * @return an opaque reference this provider's own {@link #deleteTxtRecord}
     *         needs to remove exactly this record later (e.g. a zone+record id
     *         pair) — never interpreted by the caller
     */
    String createTxtRecord(String fqdn, String value) throws Exception;

    /** Removes the record {@code recordRef} created by {@link #createTxtRecord}
     *  above. Best-effort by design in {@code AcmeService} (called from a
     *  {@code finally} block) — a cleanup failure must never fail the whole
     *  issuance, just leave a stale TXT record behind for manual removal. */
    void deleteTxtRecord(String fqdn, String recordRef) throws Exception;
}
