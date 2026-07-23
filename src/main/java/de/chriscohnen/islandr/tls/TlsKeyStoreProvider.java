package de.chriscohnen.islandr.tls;

import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.tls.runtime.KeyStoreAndKeyCertOptions;
import io.quarkus.tls.runtime.KeyStoreProvider;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Supplies the HTTP server's TLS material (ADR-0015). Quarkus calls {@link #getKeyStore}
 * whenever the default TLS configuration is created or reloaded — there is no
 * {@code quarkus.tls.*} static config for the default configuration at all; this
 * bean is the only source. Four states, resolved from {@code Settings.tlsMode}:
 *
 * <ul>
 *   <li>{@code none} (default) — the baked-in dummy placeholder certificate
 *       (src/main/resources/tls-dummy), read from the classpath into memory.
 *       Never touches the filesystem beyond the classpath resource itself.</li>
 *   <li>{@code managed} — admin-uploaded certificate stored in the DB
 *       ({@code Settings.tlsCertPem}/{@code tlsKeyPem}). The private key is
 *       decrypted only for the duration of this call when {@link EncryptionService}
 *       is configured, and is never written to disk.</li>
 *   <li>{@code referenced} — a file pair the admin points at but islandr does not
 *       own or copy (an operator's own ACME client, a CDN's origin-cert tooling).</li>
 *   <li>{@code acme} (ADR-0019) — islandr's own hand-rolled ACME client
 *       ({@code de.chriscohnen.islandr.acme}) obtains and renews the certificate
 *       itself, storing it in the same {@code tlsCertPem}/{@code tlsKeyPem} columns
 *       as {@code managed} — same loading path here, only the material's origin
 *       differs.</li>
 * </ul>
 *
 * A malformed or missing configured certificate falls back to the dummy rather
 * than taking the HTTPS listener down — see {@link #getKeyStore} catch block.
 */
@ApplicationScoped
public class TlsKeyStoreProvider implements KeyStoreProvider {

    private static final Logger LOG = Logger.getLogger(TlsKeyStoreProvider.class);

    private static final String DUMMY_CERT_RESOURCE = "/tls-dummy/dummy-cert.pem";
    private static final String DUMMY_KEY_RESOURCE = "/tls-dummy/dummy-key.pem";

    @Inject SettingsService settings;
    @Inject EncryptionService encSvc;

    @Override
    public KeyStoreAndKeyCertOptions getKeyStore(Vertx vertx) {
        // Quarkus calls getKeyStore() once during STATIC_INIT to validate the TLS
        // config, before Hibernate/the datasource (RUNTIME_INIT) is available — so
        // settings.get() must be inside this try too, not just the PEM loading. Any
        // failure at this stage (DB not up yet, no cert configured, malformed
        // material) falls back to the bundled dummy cert; it must never take the
        // HTTPS listener down.
        Settings s;
        try {
            s = settings.get();
        } catch (Exception e) {
            // Expected at the STATIC_INIT validation call — the datasource isn't up
            // yet. Not an admin-actionable problem, so stay quiet about it.
            LOG.debugf(e, "settings not available yet — using the dummy placeholder certificate");
            return dummyKeyStore(vertx);
        }
        try {
            PemKeyCertOptions options = switch (s.tlsMode) {
                // "acme" (ADR-0019) stores its issued certificate in the exact same
                // tlsCertPem/tlsKeyPem columns as "managed" — identical PEM-in-DB
                // shape, only the *source* of the material differs (AcmeService's
                // issuance flow instead of an admin upload), so it reuses this path
                // rather than needing a parallel one.
                case "managed", "acme" -> managedOptions(s.tlsCertPem, s.tlsKeyPem);
                case "referenced" -> referencedOptions(s);
                default -> dummyOptions();
            };
            return new KeyStoreAndKeyCertOptions(options.loadKeyStore(vertx), options);
        } catch (Exception e) {
            // Settings *was* available and configured something — a broken managed/
            // referenced certificate at this point is a real, admin-actionable
            // problem (bad upload, stale/rotated referenced-path file), not routine
            // startup ordering. Loud on purpose.
            LOG.errorf(e, "failed to load configured TLS material (mode=%s) — " +
                    "falling back to the dummy placeholder certificate so HTTPS stays up", s.tlsMode);
            return dummyKeyStore(vertx);
        }
    }

    private KeyStoreAndKeyCertOptions dummyKeyStore(Vertx vertx) {
        try {
            PemKeyCertOptions dummy = dummyOptions();
            return new KeyStoreAndKeyCertOptions(dummy.loadKeyStore(vertx), dummy);
        } catch (Exception fatal) {
            throw new IllegalStateException("could not load even the dummy placeholder TLS certificate", fatal);
        }
    }

    /** Builds loadable options from a cert/key PEM pair — {@code keyPem} may be either
     *  plaintext or {@link EncryptionService}-encrypted (transparently detected), so
     *  this doubles as the pre-save validation path in {@link TlsService}: build,
     *  then call {@code loadKeyStore(vertx)} on the result — a mismatched or malformed
     *  pair throws there before anything is persisted. Package-visible for that reuse. */
    PemKeyCertOptions managedOptions(String certPem, String keyPem) {
        if (certPem == null || certPem.isBlank() || keyPem == null || keyPem.isBlank()) {
            throw new IllegalStateException("tlsMode=managed but no certificate/key is stored");
        }
        String rawKeyPem = encSvc.isEncrypted(keyPem) ? encSvc.decrypt(keyPem) : keyPem;
        return new PemKeyCertOptions()
                .addCertValue(Buffer.buffer(certPem, "UTF-8"))
                .addKeyValue(Buffer.buffer(rawKeyPem, "UTF-8"));
    }

    private PemKeyCertOptions referencedOptions(Settings s) {
        if (s.tlsCertPath == null || s.tlsCertPath.isBlank() || s.tlsKeyPath == null || s.tlsKeyPath.isBlank()) {
            throw new IllegalStateException("tlsMode=referenced but no certificate/key path is configured");
        }
        return new PemKeyCertOptions()
                .addCertPath(s.tlsCertPath)
                .addKeyPath(s.tlsKeyPath);
    }

    private PemKeyCertOptions dummyOptions() {
        return new PemKeyCertOptions()
                .addCertValue(readClasspathResource(DUMMY_CERT_RESOURCE))
                .addKeyValue(readClasspathResource(DUMMY_KEY_RESOURCE));
    }

    private static Buffer readClasspathResource(String path) {
        try (InputStream in = TlsKeyStoreProvider.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("bundled resource missing: " + path);
            return Buffer.buffer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read bundled resource: " + path, e);
        }
    }
}
