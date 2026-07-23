package de.chriscohnen.islandr.acme;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * Builds and self-signs a minimal PKCS#10 certificate signing request for a
 * single-domain (one SAN, no wildcards) EC certificate — the one piece of the
 * ACME flow with no JDK builder available (ADR-0019; {@code TlsService}'s
 * PKCS1-to-PKCS8 wrapper is the precedent for hand-rolling DER here instead of
 * adding a general ASN.1/crypto library for it).
 *
 * <p>The subject's {@code SubjectPublicKeyInfo} is taken verbatim from
 * {@link java.security.PublicKey#getEncoded()} — the JDK already produces a
 * correctly DER-encoded X.509 SubjectPublicKeyInfo for EC keys, so that piece
 * does not need hand-rolling at all, only the surrounding CSR structure does.
 */
final class Csr {

    private Csr() {}

    private static final String OID_COMMON_NAME = "2.5.4.3";
    private static final String OID_EXTENSION_REQUEST = "1.2.840.113549.1.9.14";
    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";

    /** DNS SAN GeneralName tag ({@code [2] IMPLICIT IA5String} in GeneralName). */
    private static final int GENERAL_NAME_DNS_TAG = 0x82;

    /**
     * @param domain    the single DNS name this certificate is for; used as both
     *                  the subject CN (conventional, not required by Let's Encrypt)
     *                  and the sole SAN entry (what browsers actually check)
     * @param keyPair   the certificate's own keypair (distinct from the ACME
     *                  account keypair) — {@code keyPair.getPrivate()} signs the CSR
     * @return the DER-encoded {@code CertificationRequest}, ready for base64url
     *         encoding into the ACME {@code finalize} request's {@code csr} field
     */
    static byte[] build(String domain, KeyPair keyPair) throws Exception {
        byte[] subject = Der.sequence(
                Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(domain))));

        byte[] subjectPublicKeyInfo = keyPair.getPublic().getEncoded();

        byte[] generalName = Der.tagged(GENERAL_NAME_DNS_TAG, domain.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] generalNames = Der.sequence(generalName);
        byte[] sanExtension = Der.sequence(Der.oid(OID_SUBJECT_ALT_NAME), Der.octetString(generalNames));
        byte[] extensions = Der.sequence(sanExtension);
        byte[] extensionRequestAttr = Der.sequence(Der.oid(OID_EXTENSION_REQUEST), Der.set(extensions));
        byte[] attributes = Der.contextConstructed(0, extensionRequestAttr);

        byte[] certificationRequestInfo = Der.sequence(
                Der.integer(0), subject, subjectPublicKeyInfo, attributes);

        byte[] signature = signDer(certificationRequestInfo, keyPair.getPrivate());
        byte[] signatureAlgorithm = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256));

        return Der.sequence(certificationRequestInfo, signatureAlgorithm, Der.bitString(signature));
    }

    /** X.509/PKCS#10 signatures use the JCA-native DER {@code ECDSA-Sig-Value}
     *  form directly — unlike JWS (see {@link Jws}), no raw-R||S conversion here. */
    private static byte[] signDer(byte[] data, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
    }
}
