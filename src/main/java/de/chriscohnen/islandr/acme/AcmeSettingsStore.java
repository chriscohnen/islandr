package de.chriscohnen.islandr.acme;

import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * The {@code @Transactional} read/write boundary for {@link AcmeService} —
 * split into its own bean deliberately: {@code @Transactional} (like any
 * CDI interceptor binding) only applies through the injected proxy, not on
 * a same-class {@code this.method()} call, so these steps could not live as
 * transactional methods directly on {@link AcmeService} without silently
 * running outside a transaction.
 */
@ApplicationScoped
class AcmeSettingsStore {

    private static final Logger LOG = Logger.getLogger(AcmeSettingsStore.class);

    @Inject SettingsService settingsSvc;
    @Inject EncryptionService encSvc;
    @Inject TlsConfigurationRegistry registry;
    @Inject Event<CertificateUpdatedEvent> certificateUpdatedEvent;

    // These three reads are deliberately @Transactional, not plain calls to
    // settingsSvc.get() — Quarkus/Hibernate falls back to a request-scoped
    // session (with its own long-lived first-level cache) for any Settings
    // read taken outside an active transaction. issueCertificate() calls
    // domain() before any of its later @Transactional writes; without this,
    // that first non-transactional read seeds the request-scoped session's
    // cache with a pre-issuance snapshot, and every later non-transactional
    // read in the same request (e.g. SettingsResource#enableAcme's final
    // settings.get() for the REST response) silently returns that stale
    // snapshot instead of what the @Transactional writes actually committed —
    // each of those writes gets its own fresh, transaction-scoped session, so
    // the request-scoped one never observes them. @Transactional here forces
    // a fresh session (and therefore a real query) on every call instead.
    @Transactional
    boolean renewalDue(int renewalWindowDays) {
        Settings s = settingsSvc.get();
        if (!"acme".equals(s.tlsMode) || s.acmeDomain == null || s.acmeDomain.isBlank()) return false;
        if (s.tlsCertPem == null) return true;
        Instant expiry = certificateExpiry(s.tlsCertPem);
        if (expiry == null) return true; // unparseable — treat as due, same fallback as TlsService
        return Instant.now().isAfter(expiry.minus(java.time.Duration.ofDays(renewalWindowDays)));
    }

    private static Instant certificateExpiry(String certPem) {
        try {
            var in = new java.io.ByteArrayInputStream(certPem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var cert = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
            return cert.getNotAfter().toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    String domain() {
        return settingsSvc.get().acmeDomain;
    }

    @Transactional
    KeyPair ensureAccountKey() throws Exception {
        Settings s = settingsSvc.get();
        if (s.acmeAccountKeyPem != null && !s.acmeAccountKeyPem.isBlank() && s.acmeAccountPubKey != null) {
            String raw = encSvc.isEncrypted(s.acmeAccountKeyPem) ? encSvc.decrypt(s.acmeAccountKeyPem) : s.acmeAccountKeyPem;
            PrivateKey priv = parsePkcs8Ec(raw);
            PublicKey pub = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(s.acmeAccountPubKey)));
            return new KeyPair(pub, priv);
        }
        KeyPair fresh = Jws.generateEcKeyPair();
        String pem = toPkcs8Pem(fresh.getPrivate());
        s.acmeAccountKeyPem = encSvc.isConfigured() ? encSvc.encrypt(pem) : pem;
        s.acmeAccountPubKey = Base64.getEncoder().encodeToString(fresh.getPublic().getEncoded());
        return fresh;
    }

    @Transactional
    String storedAccountUrl() {
        return settingsSvc.get().acmeAccountUrl;
    }

    @Transactional
    void persistAccountUrl(String accountUrl) {
        settingsSvc.get().acmeAccountUrl = accountUrl;
    }

    @Transactional
    void persistSuccess(String certPem, String keyPem) {
        Settings s = settingsSvc.get();
        s.tlsCertPem = certPem;
        s.tlsKeyPem = encSvc.isConfigured() ? encSvc.encrypt(keyPem) : keyPem;
        s.tlsCertPath = null;
        s.tlsKeyPath = null;
        s.acmeLastAttemptAt = Instant.now();
        s.acmeLastRenewalAt = Instant.now();
        s.acmeLastError = null;
        s.updatedAt = Instant.now();
        s.updatedBy = "system:acme";
        triggerReload();
        LOG.infof("ACME: issued/renewed certificate for %s", s.acmeDomain);
    }

    @Transactional
    void persistFailure(String message) {
        Settings s = settingsSvc.get();
        s.acmeLastAttemptAt = Instant.now();
        s.acmeLastError = message;
        LOG.warnf("ACME: issuance failed for %s: %s", s.acmeDomain, message);
    }

    private void triggerReload() {
        registry.getDefault().ifPresent(config -> {
            if (config.reload()) {
                certificateUpdatedEvent.fire(new CertificateUpdatedEvent("<default>", config));
            }
        });
    }

    static String toPkcs8Pem(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static PrivateKey parsePkcs8Ec(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
