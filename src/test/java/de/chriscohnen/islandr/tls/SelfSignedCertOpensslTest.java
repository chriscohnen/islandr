package de.chriscohnen.islandr.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second, independent reader. The JDK parsing its own construction back is
 * necessary but not sufficient evidence — two implementations disagreeing on a
 * hand-encoded DER structure is exactly the failure this certificate would hit
 * in the field, where the reader is a browser and not this JVM.
 *
 * <p>Skipped where {@code openssl} is absent rather than failing: the check is
 * corroboration, not a dependency.
 */
class SelfSignedCertOpensslTest {

    @Test
    @EnabledIf("opensslAvailable")
    void opensslReadsTheCertificateAndAgreesOnTheFingerprint() throws Exception {
        SelfSignedCert.Material m =
                SelfSignedCert.generate(List.of("hub.islandr.internal", "konsole.firma.de"), 825);
        Path pem = Files.createTempFile("islandr-selfsigned", ".pem");
        try {
            Files.writeString(pem, m.certPem());

            String text = run("openssl", "x509", "-in", pem.toString(), "-noout", "-text");
            assertThat(text).contains("DNS:hub.islandr.internal", "DNS:konsole.firma.de");
            assertThat(text).contains("CA:FALSE");
            assertThat(text).contains("TLS Web Server Authentication");

            String fp = run("openssl", "x509", "-in", pem.toString(), "-noout", "-fingerprint", "-sha256");
            assertThat(fp.replace("sha256 Fingerprint=", "").trim())
                    .isEqualToIgnoringCase(m.sha256Fingerprint());
        } finally {
            Files.deleteIfExists(pem);
        }
    }

    static boolean opensslAvailable() {
        try {
            return new ProcessBuilder("openssl", "version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(String.join(" ", argv) + " → " + out).isZero();
        return out;
    }
}
