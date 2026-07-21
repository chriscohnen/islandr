package de.chriscohnen.islandr.acme;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the hand-rolled PKCS#10 builder produces a structurally correct,
 * self-consistent CSR — using a small test-local DER walker independent of
 * {@link Der} itself, so this isn't just re-checking the encoder against
 * itself. The strongest check is the one a real CA would also do first:
 * the CSR's own signature must actually verify against the embedded public key.
 */
class CsrTest {

    @Test
    void build_signatureVerifiesAgainstEmbeddedPublicKey() throws Exception {
        KeyPair keyPair = Jws.generateEcKeyPair();
        byte[] csr = Csr.build("example.com", keyPair);

        byte[][] top = readTopLevelSequence(csr);
        byte[] certificationRequestInfo = top[0];
        byte[] signature = bitStringContent(top[2]);

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(certificationRequestInfo);
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void build_embedsTheExactSubjectPublicKeyInfoFromTheKeyPair() throws Exception {
        KeyPair keyPair = Jws.generateEcKeyPair();
        byte[] csr = Csr.build("example.com", keyPair);

        assertThat(containsSubsequence(csr, keyPair.getPublic().getEncoded())).isTrue();
    }

    @Test
    void build_embedsTheDomainAsUtf8() throws Exception {
        KeyPair keyPair = Jws.generateEcKeyPair();
        byte[] csr = Csr.build("my-test-domain.example", keyPair);

        byte[] domainUtf8 = "my-test-domain.example".getBytes(StandardCharsets.US_ASCII);
        assertThat(containsSubsequence(csr, domainUtf8)).isTrue();
    }

    @Test
    void build_differentKeyPairsProduceDifferentCsrs() throws Exception {
        byte[] csr1 = Csr.build("example.com", Jws.generateEcKeyPair());
        byte[] csr2 = Csr.build("example.com", Jws.generateEcKeyPair());
        assertThat(csr1).isNotEqualTo(csr2);
    }

    /** A signature built for one CSR must not verify against a different keypair's
     *  public key — sanity check that {@link #build_signatureVerifiesAgainstEmbeddedPublicKey}
     *  isn't vacuously true (e.g. from a verifier bug that always returns true). */
    @Test
    void build_signatureDoesNotVerifyAgainstAWrongPublicKey() throws Exception {
        KeyPair keyPair = Jws.generateEcKeyPair();
        KeyPair otherKeyPair = Jws.generateEcKeyPair();
        byte[] csr = Csr.build("example.com", keyPair);

        byte[][] top = readTopLevelSequence(csr);
        byte[] signature = bitStringContent(top[2]);

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(otherKeyPair.getPublic());
        verifier.update(top[0]);
        assertThat(verifier.verify(signature)).isFalse();
    }

    // --- minimal test-local DER reader — deliberately not the production Der class ---

    /** {@code element} is a full tag+length+content BIT STRING (as returned by
     *  {@link #readElement}) — strips the tag, the length bytes, and the leading
     *  "unused bits" content byte to get to the raw signature bytes. */
    private static byte[] bitStringContent(byte[] element) {
        int[] pos = {0};
        int tag = element[pos[0]++] & 0xFF;
        if (tag != 0x03) throw new IllegalArgumentException("not a BIT STRING");
        readLength(element, pos);
        pos[0]++; // unused-bits byte, always 0x00 here (byte-aligned content)
        return Arrays.copyOfRange(element, pos[0], element.length);
    }

    /** Reads the outer {@code SEQUENCE { a, b, c }} and returns each element's
     *  full encoding (tag+length+content) for {@code CertificationRequest ::=
     *  SEQUENCE { certificationRequestInfo, signatureAlgorithm, signature }}. */
    private static byte[][] readTopLevelSequence(byte[] der) {
        int[] pos = {0};
        if (der[pos[0]++] != 0x30) throw new IllegalArgumentException("not a SEQUENCE");
        readLength(der, pos); // outer length, content starts at pos[0]
        byte[] a = readElement(der, pos);
        byte[] b = readElement(der, pos);
        byte[] c = readElement(der, pos);
        return new byte[][]{a, b, c};
    }

    private static byte[] readElement(byte[] der, int[] pos) {
        int start = pos[0];
        pos[0]++; // tag
        int len = readLength(der, pos);
        int contentStart = pos[0];
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

    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
