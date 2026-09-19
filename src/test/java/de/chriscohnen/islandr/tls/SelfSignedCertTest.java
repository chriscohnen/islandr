package de.chriscohnen.islandr.tls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A hand-rolled self-signed certificate for the hub's own names.
 *
 * <p>Needed because no public CA can ever issue for them: {@code .internal}
 * belongs to nobody, so there is nothing to prove ownership of. An installation
 * with its own domain reaches the console a different way entirely and never
 * comes through here.
 *
 * <p>These assertions parse the output back with the JDK's own X.509 reader
 * rather than comparing bytes: a certificate only this code can read is worth
 * nothing, because the party that has to accept it is a browser.
 */
class SelfSignedCertTest {

    @Test
    void theCertificateParsesAndVerifiesAgainstItself() throws Exception {
        SelfSignedCert.Material m = SelfSignedCert.generate(List.of("hub.islandr.internal"), 825);

        X509Certificate cert = parse(m.certPem());
        cert.verify(cert.getPublicKey());
        cert.checkValidity();
        assertThat(cert.getSubjectX500Principal()).isEqualTo(cert.getIssuerX500Principal());
    }

    @Test
    void everyNameGivenBecomesASubjectAlternativeName() throws Exception {
        SelfSignedCert.Material m =
                SelfSignedCert.generate(List.of("hub.islandr.internal", "konsole.firma.de"), 825);

        X509Certificate cert = parse(m.certPem());
        List<String> dnsNames = cert.getSubjectAlternativeNames().stream()
                .filter(e -> (Integer) e.get(0) == 2)
                .map(e -> (String) e.get(1))
                .toList();

        // Both, or the browser trades the issuer warning for a name-mismatch
        // warning on the second name — two warnings instead of one.
        assertThat(dnsNames).containsExactlyInAnyOrder("hub.islandr.internal", "konsole.firma.de");
    }

    @Test
    void theFirstNameIsAlsoTheSubject() throws Exception {
        SelfSignedCert.Material m =
                SelfSignedCert.generate(List.of("hub.islandr.internal", "konsole.firma.de"), 825);

        assertThat(parse(m.certPem()).getSubjectX500Principal().getName())
                .contains("hub.islandr.internal");
    }

    @Test
    void theValidityWindowStartsInThePast() throws Exception {
        SelfSignedCert.Material m = SelfSignedCert.generate(List.of("hub.islandr.internal"), 30);

        X509Certificate cert = parse(m.certPem());
        // Backdated, so a client whose clock runs a few minutes behind the hub
        // does not reject a certificate generated seconds ago. On an appliance
        // that may have booted without NTP, that is not a corner case.
        assertThat(cert.getNotBefore().toInstant()).isBefore(Instant.now());
        assertThat(cert.getNotAfter().toInstant())
                .isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void theKeyPemIsAReadablePrivateKey() throws Exception {
        SelfSignedCert.Material m = SelfSignedCert.generate(List.of("hub.islandr.internal"), 825);

        String base64 = m.keyPem()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var key = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        assertThat(key.getAlgorithm()).isEqualTo("EC");
    }

    @Test
    void theFingerprintIsTheSha256OfTheCertificateAsColonSeparatedHex() throws Exception {
        SelfSignedCert.Material m = SelfSignedCert.generate(List.of("hub.islandr.internal"), 825);

        X509Certificate cert = parse(m.certPem());
        assertThat(m.sha256Fingerprint())
                .isEqualTo(SelfSignedCert.fingerprint(cert.getEncoded()))
                .matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
    }

    @Test
    void generatingWithoutANameIsRefused() {
        assertThatThrownBy(() -> SelfSignedCert.generate(List.of(), 825))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }
}
