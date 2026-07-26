package de.chriscohnen.islandr.acme;

import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OriginCsrService} is a thin wrapper around the same {@link Jws#generateEcKeyPair()}
 * / {@link Csr#build(String, java.security.KeyPair)} crypto already covered by
 * {@link CsrTest} and {@link JwsTest} — this only checks the PEM framing it adds,
 * and that the CSR's embedded signature actually verifies against the returned
 * private key (proving the two PEM outputs genuinely describe the same keypair).
 */
class OriginCsrServiceTest {

    private final OriginCsrService svc = new OriginCsrService();

    @Test
    void generate_returnsProperlyFramedPem() {
        OriginCsrService.Generated gen = svc.generate("example.com");

        assertThat(gen.csrPem()).startsWith("-----BEGIN CERTIFICATE REQUEST-----");
        assertThat(gen.csrPem()).endsWith("-----END CERTIFICATE REQUEST-----\n");
        assertThat(gen.keyPem()).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(gen.keyPem()).endsWith("-----END PRIVATE KEY-----\n");
    }

    @Test
    void generate_keyPemActuallyPairsWithTheCsr() throws Exception {
        OriginCsrService.Generated gen = svc.generate("example.com");

        PrivateKey key = parsePkcs8Ec(gen.keyPem());
        PublicKey derivedPublic = KeyFactory.getInstance("EC")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(publicKeyDerFromCsr(gen.csrPem())));

        byte[] certificationRequestInfo = readTopLevelSequence(decodePem(gen.csrPem(), "CERTIFICATE REQUEST"))[0];
        byte[] signature = bitStringContent(readTopLevelSequence(decodePem(gen.csrPem(), "CERTIFICATE REQUEST"))[2]);

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(derivedPublic);
        verifier.update(certificationRequestInfo);
        assertThat(verifier.verify(signature)).isTrue();

        // And the private key we handed back really is the matching half — sign
        // something with it, verify with the CSR's embedded public key.
        byte[] nonce = "pairing-check".getBytes();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(nonce);
        byte[] sig = signer.sign();
        Signature verifier2 = Signature.getInstance("SHA256withECDSA");
        verifier2.initVerify(derivedPublic);
        verifier2.update(nonce);
        assertThat(verifier2.verify(sig)).isTrue();
    }

    @Test
    void generate_differentCallsProduceDifferentKeys() {
        OriginCsrService.Generated a = svc.generate("example.com");
        OriginCsrService.Generated b = svc.generate("example.com");
        assertThat(a.keyPem()).isNotEqualTo(b.keyPem());
        assertThat(a.csrPem()).isNotEqualTo(b.csrPem());
    }

    private static PrivateKey parsePkcs8Ec(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static byte[] decodePem(String pem, String label) {
        String base64 = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    /** SubjectPublicKeyInfo is the 3rd top-level element inside certificationRequestInfo
     *  (version, subject, subjectPublicKeyInfo) — read that inner sequence directly. */
    private static byte[] publicKeyDerFromCsr(String csrPem) {
        byte[] certificationRequestInfo = readTopLevelSequence(decodePem(csrPem, "CERTIFICATE REQUEST"))[0];
        int[] pos = {0};
        pos[0]++; // outer tag
        readLength(certificationRequestInfo, pos);
        readElement(certificationRequestInfo, pos); // version (INTEGER)
        readElement(certificationRequestInfo, pos); // subject (SEQUENCE)
        return readElement(certificationRequestInfo, pos); // subjectPublicKeyInfo
    }

    private static byte[] bitStringContent(byte[] element) {
        int[] pos = {0};
        int tag = element[pos[0]++] & 0xFF;
        if (tag != 0x03) throw new IllegalArgumentException("not a BIT STRING");
        readLength(element, pos);
        pos[0]++; // unused-bits byte
        return Arrays.copyOfRange(element, pos[0], element.length);
    }

    private static byte[][] readTopLevelSequence(byte[] der) {
        int[] pos = {0};
        if (der[pos[0]++] != 0x30) throw new IllegalArgumentException("not a SEQUENCE");
        readLength(der, pos);
        byte[] a = readElement(der, pos);
        byte[] b = readElement(der, pos);
        byte[] c = readElement(der, pos);
        return new byte[][]{a, b, c};
    }

    private static byte[] readElement(byte[] der, int[] pos) {
        int start = pos[0];
        pos[0]++;
        int len = readLength(der, pos);
        pos[0] += len;
        return Arrays.copyOfRange(der, start, pos[0]);
    }

    private static int readLength(byte[] der, int[] pos) {
        int b = der[pos[0]++] & 0xFF;
        if ((b & 0x80) == 0) return b;
        int n = b & 0x7F;
        int len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (der[pos[0]++] & 0xFF);
        return len;
    }
}
