package de.chriscohnen.islandr.tls;

import de.chriscohnen.islandr.acme.Der;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Builds a self-signed X.509 certificate for the hub's own names.
 *
 * <p>Why this exists at all: no public CA can issue for {@code hub.<zone>}.
 * The name belongs to nobody, so there is nothing to prove control of — ACME
 * does not fail here for want of reachability, it cannot apply. An installation
 * that owns a domain reaches the console under that domain, through a reverse
 * proxy with a real certificate, and never comes through this class. What is
 * left for everyone else is a certificate that gets accepted once, deliberately,
 * against a fingerprint.
 *
 * <p>Built by hand on {@link Der} rather than by pulling in Bouncy Castle: the
 * ACME client already hand-encodes a PKCS#10 CSR with the same primitives
 * ({@code acme/Csr.java}), a TBSCertificate is the same exercise, and the
 * binary is a GraalVM native image where every added provider is registration
 * work and startup cost. EC P-256 for the same reason the ACME code uses it —
 * small keys, universally accepted, no parameter choices to get wrong.
 */
public final class SelfSignedCert {

    private SelfSignedCert() {}

    private static final String OID_COMMON_NAME = "2.5.4.3";
    private static final String OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_EXT_KEY_USAGE = "2.5.29.37";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    /** DNS SAN GeneralName tag: {@code [2] IMPLICIT IA5String}. */
    private static final int GENERAL_NAME_DNS_TAG = 0x82;

    /**
     * Backdating. A freshly generated certificate is rejected outright by a
     * client whose clock runs behind the hub's, and an appliance that came up
     * without NTP is exactly the situation where someone needs the console.
     */
    private static final Duration_ BACKDATE = new Duration_(1);

    private record Duration_(int hours) {}

    /** The generated material, in the same PEM-in-the-database shape the
     *  managed and ACME modes already store. */
    public record Material(String certPem, String keyPem, String sha256Fingerprint, Instant notAfter) {}

    /**
     * @param dnsNames every name the certificate must cover. The first is also
     *                 the subject CN — conventional, and what a certificate
     *                 viewer shows first. All of them become SANs, which is what
     *                 browsers actually check: a missing second name would trade
     *                 the issuer warning for a name-mismatch warning instead of
     *                 avoiding one.
     * @param validityDays how long it is good for.
     */
    public static Material generate(List<String> dnsNames, int validityDays) throws Exception {
        if (dnsNames == null || dnsNames.isEmpty()) {
            throw new IllegalArgumentException("a certificate needs at least one name");
        }
        List<String> names = dnsNames.stream().map(String::trim)
                .filter(n -> !n.isEmpty()).distinct().toList();
        if (names.isEmpty()) throw new IllegalArgumentException("a certificate needs at least one name");

        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair keyPair = gen.generateKeyPair();

        Instant notBefore = Instant.now().minus(BACKDATE.hours(), ChronoUnit.HOURS);
        Instant notAfter = notBefore.plus(validityDays, ChronoUnit.DAYS);

        byte[] tbs = tbsCertificate(names, keyPair, notBefore, notAfter);
        byte[] signature = sign(tbs, keyPair.getPrivate());
        byte[] cert = Der.sequence(
                tbs,
                Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256)),
                Der.bitString(signature));

        return new Material(
                pem("CERTIFICATE", cert),
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                fingerprint(cert),
                notAfter);
    }

    /** SHA-256 over the DER certificate, as colon-separated uppercase hex —
     *  the form every browser and {@code openssl x509 -fingerprint} shows, so
     *  the two can be compared character by character without conversion. */
    public static String fingerprint(byte[] derCertificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(derCertificate);
        StringBuilder out = new StringBuilder(digest.length * 3);
        for (byte b : digest) {
            if (out.length() > 0) out.append(':');
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }

    private static byte[] tbsCertificate(List<String> names, KeyPair keyPair,
                                         Instant notBefore, Instant notAfter) {
        // [0] EXPLICIT — v3, which is what having extensions at all requires.
        byte[] version = Der.contextConstructed(0, Der.integer(2));

        // Positive, and random rather than counting from 1: a predictable serial
        // is a (small) fingerprinting handle, and nothing here needs an order.
        byte[] serialBytes = new byte[16];
        new SecureRandom().nextBytes(serialBytes);
        byte[] serial = Der.integer(new BigInteger(1, serialBytes).toByteArray());

        byte[] signatureAlg = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256));

        // Issuer == subject. That equality *is* what self-signed means.
        byte[] name = Der.sequence(
                Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(names.get(0)))));

        byte[] validity = Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter));

        byte[] spki = keyPair.getPublic().getEncoded();

        List<byte[]> sans = new ArrayList<>();
        for (String n : names) {
            sans.add(Der.tagged(GENERAL_NAME_DNS_TAG, n.getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] sanExt = extension(OID_SUBJECT_ALT_NAME, false,
                Der.sequence(sans.toArray(new byte[0][])));

        // CA:FALSE, marked critical as the profile requires. This certificate
        // signs itself and nothing else — it is a leaf that happens to be its
        // own issuer, not a little CA.
        byte[] basicConstraints = extension(OID_BASIC_CONSTRAINTS, true, Der.sequence());

        // digitalSignature + keyEncipherment: BIT STRING 101000000, one unused
        // bit stripped, which is the encoding every TLS server certificate uses.
        byte[] keyUsage = extension(OID_KEY_USAGE, true,
                Der.tagged(Der.TAG_BIT_STRING, new byte[]{0x05, (byte) 0xA0}));

        byte[] extKeyUsage = extension(OID_EXT_KEY_USAGE, false,
                Der.sequence(Der.oid(OID_SERVER_AUTH)));

        byte[] extensions = Der.contextConstructed(3,
                Der.sequence(basicConstraints, keyUsage, extKeyUsage, sanExt));

        return Der.sequence(version, serial, signatureAlg, name, validity, name, spki, extensions);
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        // DER omits a DEFAULT FALSE — writing it out is legal BER but not DER,
        // and a strict parser is exactly the audience for this certificate.
        return critical
                ? Der.sequence(Der.oid(oid), Der.bool(true), Der.octetString(value))
                : Der.sequence(Der.oid(oid), Der.octetString(value));
    }

    private static byte[] sign(byte[] tbs, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(tbs);
        return signer.sign();
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }
}
