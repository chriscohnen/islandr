package de.chriscohnen.islandr.tls;

import de.chriscohnen.islandr.dns.DnsQueryHandler;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The hub's own, self-signed certificate.
 *
 * <p>Only for the installation that has no domain. One that does reaches the
 * console under its own name through a reverse proxy, with a certificate a
 * browser already trusts, and never needs this. Here there is nothing a public
 * CA could issue against, so the honest option is a certificate that is
 * accepted once, against a fingerprint, rather than a name that cannot be
 * verified at all.
 */
@ApplicationScoped
public class HubCertificateService {

    private static final Logger LOG = Logger.getLogger(HubCertificateService.class);

    /** Just under the 825-day maximum a public CA may issue for. Not a rule
     *  that binds a self-signed certificate, but there is no reason to be
     *  looser than the ecosystem, and a certificate that outlives the hub it
     *  was made for is not a feature. */
    static final int VALIDITY_DAYS = 825;

    public static final String MODE = "selfsigned";

    @Inject SettingsService settings;

    /**
     * Generates a certificate for the hub's current names and stores it.
     *
     * <p>Replaces whatever was there: this is only ever reached for an
     * installation already on {@link #MODE} or on none at all, never for one
     * with a managed or ACME certificate — overwriting a real certificate with
     * an untrusted one would be a downgrade nobody asked for.
     *
     * @return the new certificate's fingerprint
     */
    @Transactional
    public String regenerate() throws Exception {
        Settings s = settings.get();
        if (!MODE.equals(s.tlsMode) && !"none".equals(s.tlsMode)) {
            throw new IllegalStateException(
                    "refusing to replace a " + s.tlsMode + " certificate with a self-signed one");
        }
        List<String> names = hubNames(s);
        SelfSignedCert.Material m = SelfSignedCert.generate(names, VALIDITY_DAYS);
        s.tlsMode = MODE;
        s.tlsCertPem = m.certPem();
        s.tlsKeyPem = m.keyPem();
        LOG.infof("self-signed certificate generated for %s — SHA-256 %s",
                String.join(", ", names), m.sha256Fingerprint());
        return m.sha256Fingerprint();
    }

    /**
     * The names the certificate has to cover: the fixed {@code hub.<zone>} and
     * the admin's alias when one is set. Both, or the browser trades the issuer
     * warning for a name-mismatch warning on the other one.
     */
    public List<String> hubNames(Settings s) {
        String zone = (s.dnsResolverZone == null || s.dnsResolverZone.isBlank())
                ? "islandr.internal" : s.dnsResolverZone.trim().toLowerCase();
        List<String> names = new ArrayList<>();
        names.add(DnsQueryHandler.HUB_LABEL + "." + zone);
        if (s.dnsHubAlias != null && !s.dnsHubAlias.isBlank()) {
            String alias = s.dnsHubAlias.trim().toLowerCase();
            if (!names.contains(alias)) names.add(alias);
        }
        return names;
    }

    /** Fingerprint of whatever certificate is currently stored, or null when
     *  there is none or it cannot be read. Read-only: a fingerprint nobody can
     *  compute is a display problem, never a reason to fail a settings page. */
    public String currentFingerprint(Settings s) {
        if (s.tlsCertPem == null || s.tlsCertPem.isBlank()) return null;
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                            s.tlsCertPem.getBytes(StandardCharsets.UTF_8)));
            return SelfSignedCert.fingerprint(cert.getEncoded());
        } catch (Exception e) {
            LOG.debugf(e, "could not read the stored certificate for its fingerprint");
            return null;
        }
    }

    /** True when the stored self-signed certificate no longer covers the names
     *  the hub answers for — an alias added or changed after it was generated.
     *  Worth telling an admin, because the symptom otherwise is a second
     *  browser warning nobody connects to a settings change. */
    public boolean namesOutOfDate(Settings s) {
        if (!MODE.equals(s.tlsMode) || s.tlsCertPem == null || s.tlsCertPem.isBlank()) return false;
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                            s.tlsCertPem.getBytes(StandardCharsets.UTF_8)));
            var sans = cert.getSubjectAlternativeNames();
            List<String> covered = sans == null ? List.of() : sans.stream()
                    .filter(e -> (Integer) e.get(0) == 2)
                    .map(e -> ((String) e.get(1)).toLowerCase())
                    .toList();
            return !covered.containsAll(hubNames(s));
        } catch (Exception e) {
            return false;
        }
    }
}
