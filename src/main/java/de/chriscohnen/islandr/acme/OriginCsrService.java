package de.chriscohnen.islandr.acme;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.KeyPair;
import java.util.Base64;

/**
 * Generates a private key + PKCS#10 CSR for the Settings "Origin Server
 * Certificate" tab (#42) — for an admin who wants to bring the CSR to an
 * external CA (or a CDN/proxy's origin-cert issuance form) themselves,
 * instead of pasting in an already-issued key/cert pair. Reuses the same
 * EC keygen and CSR-building code ACME already has ({@link Jws#generateEcKeyPair()},
 * {@link Csr#build(String, KeyPair)}) — this is the same crypto, just for a
 * key the operator controls rather than an ACME account/certificate key.
 */
@ApplicationScoped
public class OriginCsrService {

    public record Generated(String csrPem, String keyPem) {}

    public Generated generate(String domain) {
        try {
            KeyPair keyPair = Jws.generateEcKeyPair();
            byte[] csrDer = Csr.build(domain, keyPair);
            return new Generated(pem("CERTIFICATE REQUEST", csrDer), AcmeSettingsStore.toPkcs8Pem(keyPair.getPrivate()));
        } catch (Exception e) {
            throw new RuntimeException("could not generate CSR: " + e.getMessage(), e);
        }
    }

    private static String pem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }
}
