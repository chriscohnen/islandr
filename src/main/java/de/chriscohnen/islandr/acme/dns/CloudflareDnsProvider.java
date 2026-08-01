package de.chriscohnen.islandr.acme.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.acme.AcmeException;
import de.chriscohnen.islandr.identity.HttpFetcher;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * DNS-01 provider for Cloudflare-managed zones (ADR-0020), using Cloudflare's
 * API v4 directly — no SDK, same "hand-roll the HTTP calls" precedent as the
 * ACME client itself (ADR-0019). Needs an API token scoped to
 * {@code Zone:DNS:Edit} for the zone(s) it will be asked to publish records in.
 */
public final class CloudflareDnsProvider implements DnsProvider {

    private static final String API_BASE = "https://api.cloudflare.com/client/v4";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpFetcher http;
    private final String apiToken;

    public CloudflareDnsProvider(HttpFetcher http, String apiToken) {
        this.http = http;
        this.apiToken = apiToken;
    }

    @Override
    public String createTxtRecord(String fqdn, String value) throws Exception {
        String zoneId = findZoneId(fqdn);
        String body = JSON.writeValueAsString(Map.of(
                "type", "TXT",
                "name", "_acme-challenge." + fqdn,
                "content", value,
                "ttl", 120));
        HttpFetcher.Response resp = http.postBody(API_BASE + "/zones/" + zoneId + "/dns_records",
                body.getBytes(StandardCharsets.UTF_8), "application/json", authHeaders());
        JsonNode json = JSON.readTree(resp.body());
        if (!json.path("success").asBoolean(false)) {
            throw new AcmeException("Cloudflare createTxtRecord failed: " + json.path("errors"));
        }
        return zoneId + ":" + json.get("result").get("id").asText();
    }

    @Override
    public void deleteTxtRecord(String fqdn, String recordRef) throws Exception {
        String[] parts = recordRef.split(":", 2);
        if (parts.length != 2) throw new AcmeException("malformed Cloudflare record reference: " + recordRef);
        http.delete(API_BASE + "/zones/" + parts[0] + "/dns_records/" + parts[1], authHeaders());
    }

    /**
     * Cloudflare's zone-lookup only matches the exact registrable zone name
     * ({@code GET /zones?name=X}), not a subdomain within it — {@code fqdn} may
     * be a subdomain of the zone (e.g. {@code vpn.example.com} in the
     * {@code example.com} zone), so this walks up the label chain (dropping
     * the leftmost label each time) until a zone matches, the same technique
     * every DNS-01 ACME client uses since there is no reliable way to ask "what
     * zone owns this name" other than trying candidates.
     */
    private String findZoneId(String fqdn) throws Exception {
        String candidate = fqdn;
        while (true) {
            HttpFetcher.Response resp = http.get(API_BASE + "/zones?name=" + candidate, authHeaders());
            JsonNode json = JSON.readTree(resp.body());
            JsonNode results = json.path("result");
            if (results.isArray() && results.size() > 0) {
                return results.get(0).get("id").asText();
            }
            int dot = candidate.indexOf('.');
            if (dot < 0) throw new AcmeException("no Cloudflare zone found for domain " + fqdn);
            candidate = candidate.substring(dot + 1);
        }
    }

    private Map<String, String> authHeaders() {
        return Map.of(
                "Authorization", "Bearer " + apiToken,
                "Content-Type", "application/json");
    }
}
