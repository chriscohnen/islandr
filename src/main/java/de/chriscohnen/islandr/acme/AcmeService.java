package de.chriscohnen.islandr.acme;

import com.fasterxml.jackson.databind.JsonNode;
import de.chriscohnen.islandr.acme.dns.CloudflareDnsProvider;
import de.chriscohnen.islandr.acme.dns.DnsProvider;
import de.chriscohnen.islandr.identity.HttpFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
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

    private static final Logger LOG = Logger.getLogger(AcmeService.class);
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @Inject AcmeSettingsStore store;
    @Inject ChallengeHolder challenges;
    @Inject HttpFetcher http;

    @ConfigProperty(name = "islandr.acme.directory-url") String directoryUrl;
    @ConfigProperty(name = "islandr.acme.renewal-window-days") int renewalWindowDays;
    @ConfigProperty(name = "islandr.acme.poll-interval") Duration pollInterval;
    @ConfigProperty(name = "islandr.acme.poll-timeout") Duration pollTimeout;
    // dns-01/Cloudflare only (ADR-0020) — a fixed wait after publishing the TXT
    // record, instead of actively polling DNS. Actively polling would need the
    // JDK's internal JNDI DNS resolver (com.sun.jndi.dns), which risks a
    // GraalVM native-image module/reflection problem this project has been
    // burned by before (ADR-0004 R-034) for a feature this narrow; a fixed
    // delay is simpler and has no such risk, at the cost of not adapting to
    // how fast a given DNS provider actually propagates.
    @ConfigProperty(name = "islandr.acme.dns-propagation-wait") Duration dnsPropagationWait;

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
     * <p>When the dns-01 challenge type is set to the "manual" provider
     * (ADR-0020), this method does <em>not</em> reach a terminal state on its
     * own — it persists the TXT record to add and the in-flight order state,
     * then returns normally (not a failure) so the caller can show the record
     * to the admin. {@link #continueManualDnsChallenge()} finishes the flow
     * once they've added it.
     *
     * @throws AcmeException on any protocol failure, after recording it
     */
    public void issueCertificate() {
        String domain = store.domain();
        if (domain == null || domain.isBlank()) {
            throw new AcmeException("no acmeDomain configured");
        }
        AcmeSettingsStore.DnsChallengeConfig dnsCfg = store.dnsChallengeConfig();
        try {
            validateDnsConfig(dnsCfg);

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

            if ("dns-01".equals(dnsCfg.challengeType())) {
                if ("manual".equals(dnsCfg.provider())) {
                    respondToManualDns01Challenge(client, authzUrl, orderUrl, finalizeUrl, thumbprint, domain);
                    return; // pending — continueManualDnsChallenge() finishes this later
                }
                respondToDns01Challenge(client, authzUrl, thumbprint, domain, dnsCfg);
            } else {
                respondToHttp01Challenge(client, authzUrl, thumbprint);
            }

            finalizeAndDownload(client, finalizeUrl, orderUrl, domain);
        } catch (AcmeException e) {
            store.persistFailure(e.getMessage());
            throw e;
        } catch (Exception e) {
            store.persistFailure(e.getMessage());
            throw new AcmeException("ACME issuance failed for an unexpected reason", e);
        }
    }

    /** Fails fast on a dns-01 misconfiguration before wasting an ACME order
     *  attempt on it — checked once, upfront, rather than deep inside
     *  {@link #respondToDns01Challenge} where the network round-trips have
     *  already happened. */
    private static void validateDnsConfig(AcmeSettingsStore.DnsChallengeConfig dnsCfg) {
        if (!"dns-01".equals(dnsCfg.challengeType()) || "manual".equals(dnsCfg.provider())) {
            return; // http-01, or manual (no API token needed)
        }
        if (!"cloudflare".equals(dnsCfg.provider())) {
            throw new AcmeException("unsupported DNS provider: " + dnsCfg.provider() + " (only 'cloudflare' is automated — use 'manual' for any other host)");
        }
        if (dnsCfg.apiToken() == null || dnsCfg.apiToken().isBlank()) {
            throw new AcmeException("dns-01 is selected but no DNS provider API token is configured");
        }
    }

    /** Resumes a "manual" dns-01 challenge (ADR-0020) after the admin has added
     *  the TXT record {@link #issueCertificate()} asked for. Re-establishes its
     *  own {@link AcmeClient} (a fresh nonce, same account) rather than trying
     *  to keep one alive across two separate HTTP requests. */
    public void continueManualDnsChallenge() {
        AcmeSettingsStore.PendingManualDns pending = store.manualDnsPending();
        if (pending == null) {
            AcmeException e = new AcmeException("no manual DNS-01 challenge is currently pending");
            store.persistFailure(e.getMessage());
            throw e;
        }
        String domain = store.domain();
        if (domain == null || domain.isBlank()) {
            AcmeException e = new AcmeException("no acmeDomain configured");
            store.persistFailure(e.getMessage());
            throw e;
        }
        try {
            KeyPair accountKeyPair = store.ensureAccountKey();
            AcmeClient client = new AcmeClient(http, accountKeyPair.getPrivate(), new LinkedHashMap<>(
                    Map.of("jwk", Jws.jwk((ECPublicKey) accountKeyPair.getPublic()))));
            AcmeClient.Directory dir = client.directory(directoryUrl);
            client.primeNonce(dir.newNonce());
            String accountUrl = store.storedAccountUrl();
            if (accountUrl == null) throw new AcmeException("no ACME account on file — the pending challenge is stale, start over");
            client.useAccountUrl(accountUrl);

            client.post(pending.challengeUrl(), Map.of());
            pollUntil(client, pending.authzUrl(), "valid",
                    body -> "valid".equals(body.get("status").asText()) ? "done" : null);

            finalizeAndDownload(client, pending.finalizeUrl(), pending.orderUrl(), domain);
            store.clearManualDnsPending();
        } catch (AcmeException e) {
            store.persistFailure(e.getMessage());
            throw e;
        } catch (Exception e) {
            store.persistFailure(e.getMessage());
            throw new AcmeException("ACME dns-01 continuation failed for an unexpected reason", e);
        }
    }

    /** Cancels an in-progress ACME onboarding attempt (issue: "onboarding
     *  should always be abortable", both http-01 and dns-01). Always clears a
     *  stuck manual dns-01 pending challenge; only resets the TLS mode back
     *  to "none" if no certificate has actually been issued yet, so cancelling
     *  a stuck first attempt never discards an already-working certificate —
     *  see {@link AcmeSettingsStore#cancelPendingSetup}. Synchronous http-01 /
     *  Cloudflare dns-01 attempts already in flight on the server finish on
     *  their own regardless (no async job to actually interrupt); this clears
     *  the state so the UI can offer a clean retry instead of being stuck
     *  showing a pending challenge or a settings row mid-attempt. */
    public void cancel(String actor) {
        store.cancelPendingSetup(actor);
    }

    /** The shared tail of the flow once a challenge (of any type) has been
     *  satisfied: generate the certificate's own keypair, finalize with a CSR
     *  for it, poll until the certificate is ready, download, persist. */
    private void finalizeAndDownload(AcmeClient client, String finalizeUrl, String orderUrl, String domain) throws Exception {
        KeyPair certKeyPair = Jws.generateEcKeyPair();
        byte[] csrDer = Csr.build(domain, certKeyPair);
        client.post(finalizeUrl, Map.of("csr", B64URL.encodeToString(csrDer)));

        String certificateUrl = pollUntil(client, orderUrl, "valid",
                body -> body.has("certificate") ? body.get("certificate").asText() : null);

        String certPem = client.postAsGetRaw(certificateUrl);
        String keyPem = AcmeSettingsStore.toPkcs8Pem(certKeyPair.getPrivate());

        store.persistSuccess(certPem, keyPem);
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
        JsonNode http01 = findChallenge(client, authzUrl, "http-01");

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

    /** Automatic dns-01 via a {@link DnsProvider} (Cloudflare in v1, ADR-0020):
     *  publish the TXT record, wait a fixed propagation delay, notify, poll,
     *  then always remove the record again regardless of outcome. Provider/
     *  token validity is already checked by {@link #validateDnsConfig} before
     *  this is ever called. */
    private void respondToDns01Challenge(AcmeClient client, String authzUrl, String thumbprint,
                                          String domain, AcmeSettingsStore.DnsChallengeConfig dnsCfg) throws Exception {
        DnsProvider provider = new CloudflareDnsProvider(http, dnsCfg.apiToken());

        JsonNode dns01 = findChallenge(client, authzUrl, "dns-01");
        String challengeUrl = dns01.get("url").asText();
        String digest = base64UrlSha256(dns01.get("token").asText() + "." + thumbprint);

        String recordRef = provider.createTxtRecord(domain, digest);
        try {
            sleep(dnsPropagationWait);
            client.post(challengeUrl, Map.of());
            pollUntil(client, authzUrl, "valid", body -> "valid".equals(body.get("status").asText()) ? "done" : null);
        } finally {
            try {
                provider.deleteTxtRecord(domain, recordRef);
            } catch (Exception e) {
                // Best-effort cleanup — a stale TXT record left behind is a
                // minor annoyance, not worth failing an otherwise-successful
                // issuance over.
                LOG.warnf("ACME: could not remove dns-01 TXT record for %s (leaving it behind): %s", domain, e.getMessage());
            }
        }
    }

    /** "manual" dns-01 provider (ADR-0020, no API automation): compute the
     *  challenge and persist the order state, but do not respond to it —
     *  {@link #continueManualDnsChallenge()} does that once the admin has
     *  added the TXT record themselves. */
    private void respondToManualDns01Challenge(AcmeClient client, String authzUrl, String orderUrl, String finalizeUrl,
                                                String thumbprint, String domain) throws Exception {
        JsonNode dns01 = findChallenge(client, authzUrl, "dns-01");
        String challengeUrl = dns01.get("url").asText();
        String digest = base64UrlSha256(dns01.get("token").asText() + "." + thumbprint);
        String recordName = "_acme-challenge." + domain;
        store.persistManualDnsPending(recordName, digest, orderUrl, authzUrl, challengeUrl, finalizeUrl);
    }

    private static JsonNode findChallenge(AcmeClient client, String authzUrl, String type) {
        AcmeClient.AcmeResponse authz = client.postAsGet(authzUrl);
        for (JsonNode c : authz.body().get("challenges")) {
            if (type.equals(c.get("type").asText())) return c;
        }
        throw new AcmeException("no " + type + " challenge offered for this authorization");
    }

    /** RFC 8555 §8.4 — the dns-01 TXT record value is the base64url SHA-256
     *  digest of the key authorization (http-01 serves the key authorization
     *  itself, unhashed; dns-01 does not, to keep the TXT record short). */
    private static String base64UrlSha256(String keyAuthorization) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyAuthorization.getBytes(StandardCharsets.UTF_8));
        return B64URL.encodeToString(digest);
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
