package de.chriscohnen.islandr.acme;

import com.fasterxml.jackson.databind.JsonNode;
import de.chriscohnen.islandr.identity.HttpFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the RFC 8555 issuance flow for the one domain islandr manages
 * in ACME mode (ADR-0019): account (create-or-reuse) → order → HTTP-01
 * challenge → finalize → download. Everything protocol-generic lives in
 * {@link AcmeClient}; every DB read/write goes through {@link AcmeSettingsStore}
 * (its own bean so {@code @Transactional} actually applies — see that class's
 * javadoc). This class holds only the orchestration logic in between.
 *
 * <p>The slow part of this flow (network round-trips, and the poll loops
 * waiting on Let's Encrypt's own validation/issuance latency) deliberately
 * runs with no database transaction open — only the short steps delegated to
 * {@link AcmeSettingsStore} are transactional, so a slow or stalled ACME
 * server never holds one open.
 */
@ApplicationScoped
public class AcmeService {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @Inject AcmeSettingsStore store;
    @Inject ChallengeHolder challenges;
    @Inject HttpFetcher http;

    @ConfigProperty(name = "islandr.acme.directory-url") String directoryUrl;
    @ConfigProperty(name = "islandr.acme.renewal-window-days") int renewalWindowDays;
    @ConfigProperty(name = "islandr.acme.poll-interval") Duration pollInterval;
    @ConfigProperty(name = "islandr.acme.poll-timeout") Duration pollTimeout;

    /** True when ACME mode is on and the current certificate is missing or due
     *  for renewal — what both the boot check and the daily scheduler ask. */
    public boolean renewalDue() {
        return store.renewalDue(renewalWindowDays);
    }

    /**
     * Runs one full issuance/renewal attempt for the configured ACME domain.
     * On success, the new certificate is stored and hot-reloaded exactly like
     * a manual "managed" upload; on failure, the previous certificate keeps
     * serving and the error is recorded for the Settings status display —
     * never a state where HTTPS goes down because a renewal failed.
     *
     * @throws AcmeException on any protocol failure, after recording it
     */
    public void issueCertificate() {
        String domain = store.domain();
        if (domain == null || domain.isBlank()) {
            throw new AcmeException("no acmeDomain configured");
        }
        try {
            KeyPair accountKeyPair = store.ensureAccountKey();
            AcmeClient client = new AcmeClient(http, accountKeyPair.getPrivate(), new LinkedHashMap<>(
                    Map.of("jwk", Jws.jwk((ECPublicKey) accountKeyPair.getPublic()))));

            AcmeClient.Directory dir = client.directory(directoryUrl);
            client.primeNonce(dir.newNonce());

            String accountUrl = ensureAccount(client, dir);
            client.useAccountUrl(accountUrl);

            String orderUrl;
            JsonNode order;
            {
                AcmeClient.AcmeResponse resp = client.post(dir.newOrder(),
                        Map.of("identifiers", List.of(Map.of("type", "dns", "value", domain))));
                orderUrl = resp.location();
                order = resp.body();
            }

            String authzUrl = order.get("authorizations").get(0).asText();
            String finalizeUrl = order.get("finalize").asText();

            String thumbprint = Jws.thumbprint((ECPublicKey) accountKeyPair.getPublic());
            respondToHttp01Challenge(client, authzUrl, thumbprint);

            KeyPair certKeyPair = Jws.generateEcKeyPair();
            byte[] csrDer = Csr.build(domain, certKeyPair);
            client.post(finalizeUrl, Map.of("csr", B64URL.encodeToString(csrDer)));

            String certificateUrl = pollUntil(client, orderUrl, "valid",
                    body -> body.has("certificate") ? body.get("certificate").asText() : null);

            String certPem = client.postAsGetRaw(certificateUrl);
            String keyPem = AcmeSettingsStore.toPkcs8Pem(certKeyPair.getPrivate());

            store.persistSuccess(certPem, keyPem);
        } catch (AcmeException e) {
            store.persistFailure(e.getMessage());
            throw e;
        } catch (Exception e) {
            store.persistFailure(e.getMessage());
            throw new AcmeException("ACME issuance failed for an unexpected reason", e);
        }
    }

    /** newAccount is idempotent — POSTing the same account key again just
     *  returns the existing account (200, not 201) with the same Location, so
     *  this is always safe to call and self-heals if the stored account URL
     *  ever drifted from what Let's Encrypt actually has on file. */
    private String ensureAccount(AcmeClient client, AcmeClient.Directory dir) {
        AcmeClient.AcmeResponse resp = client.post(dir.newAccount(), Map.of("termsOfServiceAgreed", true));
        String accountUrl = resp.location();
        if (accountUrl == null) throw new AcmeException("newAccount response had no Location header");
        if (!accountUrl.equals(store.storedAccountUrl())) {
            store.persistAccountUrl(accountUrl);
        }
        return accountUrl;
    }

    private void respondToHttp01Challenge(AcmeClient client, String authzUrl, String thumbprint) {
        AcmeClient.AcmeResponse authz = client.postAsGet(authzUrl);
        JsonNode http01 = null;
        for (JsonNode c : authz.body().get("challenges")) {
            if ("http-01".equals(c.get("type").asText())) { http01 = c; break; }
        }
        if (http01 == null) throw new AcmeException("no http-01 challenge offered for this authorization");

        String token = http01.get("token").asText();
        String challengeUrl = http01.get("url").asText();
        String keyAuthorization = token + "." + thumbprint;

        challenges.set(token, keyAuthorization);
        try {
            client.post(challengeUrl, Map.of());
            pollUntil(client, authzUrl, "valid", body -> "valid".equals(body.get("status").asText()) ? "done" : null);
        } finally {
            challenges.clear();
        }
    }

    /** Polls {@code url} (POST-as-GET) until {@code extractor} returns non-null
     *  (reached the target status) or the authorization/order status is
     *  {@code invalid} (fails fast with the server's own problem detail if
     *  present) or {@link #pollTimeout} elapses. */
    private String pollUntil(AcmeClient client, String url, String targetStatusHint,
                              java.util.function.Function<JsonNode, String> extractor) {
        Instant deadline = Instant.now().plus(pollTimeout);
        while (true) {
            AcmeClient.AcmeResponse resp = client.postAsGet(url);
            JsonNode body = resp.body();
            String status = body.has("status") ? body.get("status").asText() : null;
            String result = extractor.apply(body);
            if (result != null) return result;
            if ("invalid".equals(status)) {
                String detail = body.has("error") ? body.get("error").toString() : body.toString();
                throw new AcmeException("ACME " + url + " became invalid: " + detail);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AcmeException("timed out waiting for " + url + " to reach '" + targetStatusHint
                        + "' (last status: " + status + ")");
            }
            sleep(pollInterval);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeException("interrupted while waiting for ACME server", e);
        }
    }
}
