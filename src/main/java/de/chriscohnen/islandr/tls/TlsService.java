package de.chriscohnen.islandr.tls;

import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and persists the managed-mode TLS certificate (ADR-0015), and triggers
 * the same reload + {@link CertificateUpdatedEvent} sequence {@link TlsKeyStoreProvider}
 * needs to actually pick up new material — a save that doesn't reload would silently
 * keep serving the previous certificate until the next unrelated reload.
 */
@ApplicationScoped
public class TlsService {

    private static final Logger LOG = Logger.getLogger(TlsService.class);

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----.*?-----END \\1-----", Pattern.DOTALL);

    public record PemBundle(String certPem, String keyPem) {}

    @Inject SettingsService settingsSvc;
    @Inject EncryptionService encSvc;
    @Inject TlsKeyStoreProvider provider;
    @Inject TlsConfigurationRegistry registry;
    @Inject Event<CertificateUpdatedEvent> certificateUpdatedEvent;
    @Inject Vertx vertx;

    /** {@link TlsKeyStoreProvider#getKeyStore} is invoked once during STATIC_INIT to
     *  validate the TLS config — before the datasource is up, so it always falls back
     *  to the dummy certificate at that point (see the comment there). Nothing else
     *  re-invokes the provider automatically afterwards, so without this, a boot with
     *  a real managed/referenced certificate already configured would keep serving the
     *  dummy for the server's entire lifetime. StartupEvent fires after RUNTIME_INIT,
     *  once the datasource is genuinely available — reload once here so real
     *  configuration actually takes effect. */
    void onStartup(@Observes StartupEvent ev) {
        triggerReload();
    }

    /** Splits one pasted PEM blob — certificate(s) and private key in either order,
     *  the shape an admin gets handed by e.g. Cloudflare's Origin CA generator or a
     *  `cat cert.pem key.pem` — into the separate cert/key strings the rest of this
     *  class works with. Every certificate-labelled block is kept and concatenated in
     *  its original order, so a leaf + intermediate chain still works exactly as it
     *  did when cert and key were two separate fields; only the private key is
     *  required to be singular, since that's what actually identifies "the" key. */
    public static PemBundle splitPemBundle(String combined) {
        if (combined == null || combined.isBlank()) {
            throw new WebApplicationException("paste the certificate and private key (PEM)", 400);
        }
        List<String> certBlocks = new ArrayList<>();
        String keyBlock = null;
        int keyCount = 0;
        Matcher m = PEM_BLOCK.matcher(combined);
        while (m.find()) {
            String label = m.group(1);
            String block = m.group();
            if ("CERTIFICATE".equals(label)) {
                certBlocks.add(block);
            } else if (label.endsWith("PRIVATE KEY")) {
                keyBlock = block;
                keyCount++;
            }
            // Anything else (a stray CSR, a PUBLIC KEY block, …) is ignored rather
            // than rejected outright — the cert/key presence checks below still
            // catch a genuinely incomplete or wrong paste.
        }
        if (certBlocks.isEmpty()) {
            throw new WebApplicationException(
                    "no certificate found — expected a '-----BEGIN CERTIFICATE-----' block", 400);
        }
        if (keyCount == 0) {
            throw new WebApplicationException(
                    "no private key found — expected a '-----BEGIN PRIVATE KEY-----' or "
                            + "'-----BEGIN RSA PRIVATE KEY-----' block", 400);
        }
        if (keyCount > 1) {
            throw new WebApplicationException("more than one private key block found — paste only one", 400);
        }
        return new PemBundle(String.join("\n", certBlocks), keyBlock);
    }

    @Transactional
    public Settings updateManagedCertificate(String certPem, String keyPem, String actor) {
        X509Certificate cert = requireCurrentlyValid(certPem);
        requireMatchingPair(cert, keyPem);
        requireLoadable(certPem, keyPem);

        Settings s = settingsSvc.get();
        s.tlsMode = "managed";
        s.tlsCertPem = certPem;
        s.tlsKeyPem = encSvc.isConfigured() ? encSvc.encrypt(keyPem) : keyPem;
        s.tlsCertPath = null;
        s.tlsKeyPath = null;
        s.updatedAt = Instant.now();
        s.updatedBy = actor;
        triggerReload();
        return s;
    }

    @Transactional
    public Settings resetToDummy(String actor) {
        Settings s = settingsSvc.get();
        s.tlsMode = "none";
        s.tlsCertPem = null;
        s.tlsKeyPem = null;
        s.tlsCertPath = null;
        s.tlsKeyPath = null;
        s.updatedAt = Instant.now();
        s.updatedBy = actor;
        triggerReload();
        return s;
    }

    /** Powers the Settings expiry-date banner (ADR-0015 R-153) — null when the cert
     *  can't be parsed for any reason, so a display problem never becomes a 500. */
    public Instant certificateExpiresAt(String certPem) {
        if (certPem == null || certPem.isBlank()) return null;
        try {
            return parseCertificate(certPem).getNotAfter().toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Basic X.509 sanity (ADR-0015 R-152) — reject an already-expired or not-yet-valid
     *  certificate before it ever reaches storage or the keystore-loading step. */
    private X509Certificate requireCurrentlyValid(String certPem) {
        X509Certificate cert;
        try {
            cert = parseCertificate(certPem);
        } catch (Exception e) {
            throw new WebApplicationException("could not parse certificate — expected PEM-encoded X.509: " + e.getMessage(), 400);
        }
        try {
            cert.checkValidity();
        } catch (java.security.cert.CertificateExpiredException | java.security.cert.CertificateNotYetValidException e) {
            throw new WebApplicationException("certificate is not currently valid: " + e.getMessage(), 400);
        }
        return cert;
    }

    private static X509Certificate parseCertificate(String certPem) throws CertificateException, java.io.IOException {
        try (var in = new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /** A keystore does not cryptographically verify that a private key matches
     *  a certificate's public key at load time — it just stores the pairing as given
     *  ({@link #requireLoadable} alone would accept a mismatched pair). Proves the pairing
     *  the same way a real TLS handshake would: sign a nonce with the private key, verify
     *  it with the certificate's public key. */
    private void requireMatchingPair(X509Certificate cert, String keyPem) {
        PrivateKey key;
        try {
            key = parsePrivateKeyPem(keyPem, cert.getPublicKey().getAlgorithm());
        } catch (Exception e) {
            throw new WebApplicationException("could not parse private key: " + e.getMessage(), 400);
        }
        try {
            byte[] nonce = "islandr-tls-pairing-check".getBytes(StandardCharsets.UTF_8);
            String sigAlg = "EC".equals(cert.getPublicKey().getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
            Signature signer = Signature.getInstance(sigAlg);
            signer.initSign(key);
            signer.update(nonce);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(sigAlg);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(nonce);
            if (!verifier.verify(signature)) {
                throw new WebApplicationException("the private key does not match the certificate's public key", 400);
            }
        } catch (GeneralSecurityException e) {
            throw new WebApplicationException("could not verify certificate/key pairing: " + e.getMessage(), 400);
        }
    }

    /** Accepts the three PEM private-key forms an operator is realistically going to
     *  paste: PKCS8 ("BEGIN PRIVATE KEY", what most modern tooling emits), and legacy
     *  PKCS1 RSA ("BEGIN RSA PRIVATE KEY") — notably what Cloudflare's Origin CA
     *  certificate generator hands back for an RSA key. {@link TlsKeyStoreProvider}
     *  and the underlying Vert.x PEM loader already accept all three natively at
     *  serve time; this parser exists only to get a {@link PrivateKey} object for the
     *  pairing check above, so it has to keep pace with what actually gets accepted
     *  there. SEC1 EC keys ("BEGIN EC PRIVATE KEY") are the one form still rejected —
     *  clearly, with a conversion command, rather than silently mis-parsed. */
    private static PrivateKey parsePrivateKeyPem(String keyPem, String algorithm) throws GeneralSecurityException {
        if (keyPem.contains("BEGIN RSA PRIVATE KEY")) {
            if (!"RSA".equals(algorithm)) {
                throw new InvalidKeyException(
                        "the certificate's public key is " + algorithm + ", but the private key is a PKCS1 RSA key");
            }
            byte[] pkcs1Der = decodePemBody(keyPem, "RSA PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1RsaKeyAsPkcs8(pkcs1Der)));
        }
        if (keyPem.contains("BEGIN EC PRIVATE KEY")) {
            throw new InvalidKeyException(
                    "SEC1 EC private keys ('BEGIN EC PRIVATE KEY') aren't supported — convert first with "
                            + "'openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem'");
        }
        if (keyPem.contains("BEGIN PRIVATE KEY")) {
            byte[] der = decodePemBody(keyPem, "PRIVATE KEY");
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        throw new InvalidKeyException(
                "no recognised PEM private-key header — expected 'BEGIN PRIVATE KEY' or 'BEGIN RSA PRIVATE KEY'");
    }

    private static byte[] decodePemBody(String pem, String label) {
        String base64 = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    /** Wraps a PKCS1 RSAPrivateKey DER structure in the minimal PKCS8 PrivateKeyInfo
     *  envelope (RFC 5958) so the JDK's {@link KeyFactory} — which only ever accepts
     *  PKCS8 — can parse it. The AlgorithmIdentifier is the fixed 15-byte DER encoding
     *  of {rsaEncryption OID 1.2.840.113549.1.1.1, NULL params}; the PKCS1 bytes are
     *  carried unmodified inside the trailing OCTET STRING. */
    private static byte[] wrapPkcs1RsaKeyAsPkcs8(byte[] pkcs1Der) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] rsaAlgorithmId = {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00,
        };
        byte[] octetString = derEncode(0x04, pkcs1Der);
        byte[] body = new byte[version.length + rsaAlgorithmId.length + octetString.length];
        System.arraycopy(version, 0, body, 0, version.length);
        System.arraycopy(rsaAlgorithmId, 0, body, version.length, rsaAlgorithmId.length);
        System.arraycopy(octetString, 0, body, version.length + rsaAlgorithmId.length, octetString.length);
        return derEncode(0x30, body);
    }

    private static byte[] derEncode(int tag, byte[] content) {
        byte[] length = derLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    private static byte[] derLength(int len) {
        if (len < 0x80) return new byte[]{(byte) len};
        int byteCount = 1;
        int tmp = len;
        while ((tmp >>>= 8) != 0) byteCount++;
        byte[] out = new byte[byteCount + 1];
        out[0] = (byte) (0x80 | byteCount);
        for (int i = byteCount; i >= 1; i--) {
            out[i] = (byte) (len & 0xFF);
            len >>>= 8;
        }
        return out;
    }

    /** Reuses {@link TlsKeyStoreProvider}'s own loading path — the exact same code that
     *  will run on every future reload — so malformed PEM is rejected here, synchronously,
     *  with a clear error, instead of surfacing only later as a silent fallback to the
     *  dummy certificate. Pairing itself is already proven by {@link #requireMatchingPair}. */
    private void requireLoadable(String certPem, String keyPem) {
        try {
            PemKeyCertOptions options = provider.managedOptions(certPem, keyPem);
            options.loadKeyStore(vertx);
        } catch (Exception e) {
            throw new WebApplicationException(
                    "certificate/key could not be loaded — check they are valid PEM and match each other: "
                            + e.getMessage(), 400);
        }
    }

    private void triggerReload() {
        registry.getDefault().ifPresent(config -> {
            if (config.reload()) {
                certificateUpdatedEvent.fire(new CertificateUpdatedEvent("<default>", config));
            }
        });
    }
}
