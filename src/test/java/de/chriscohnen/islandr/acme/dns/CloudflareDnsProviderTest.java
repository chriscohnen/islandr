package de.chriscohnen.islandr.acme.dns;

import de.chriscohnen.islandr.acme.AcmeException;
import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the zone-lookup edge cases {@code AcmeDns01Test}'s end-to-end
 * happy path doesn't exercise directly: an exact-match zone (no walk-up
 * needed) and no zone found at all. Uses {@link FakeHttpFetcher} directly —
 * this is a {@code @QuarkusTest} only to get that CDI-mocked fetcher, not
 * because {@link CloudflareDnsProvider} itself needs any injection.
 */
@QuarkusTest
class CloudflareDnsProviderTest {

    @Inject FakeHttpFetcher http;

    @BeforeEach
    void reset() {
        http.reset();
    }

    @Test
    void createTxtRecord_exactZoneMatch_noWalkUpNeeded() throws Exception {
        http.stubJson("https://api.cloudflare.com/client/v4/zones?name=example.com", 200, """
                {"result":[{"id":"zone-x"}]}
                """);
        http.postBodyStub("https://api.cloudflare.com/client/v4/zones/zone-x/dns_records", 200, """
                {"success":true,"result":{"id":"rec-x"}}
                """, Map.of("content-type", "application/json"));

        CloudflareDnsProvider provider = new CloudflareDnsProvider(http, "token");
        String recordRef = provider.createTxtRecord("example.com", "digest-value");

        assertThat(recordRef).isEqualTo("zone-x:rec-x");
        // Only the exact-name lookup was needed — no walk-up query fired.
        assertThat(FakeHttpFetcher.calls.stream()
                .filter(c -> "GET".equals(c.method()))
                .count()).isEqualTo(1);
    }

    @Test
    void createTxtRecord_noZoneFoundForAnySuffix_throws() {
        // Every candidate (vpn.example.com, example.com) returns no zones.
        http.stubJson("https://api.cloudflare.com/client/v4/zones?name=vpn.example.com", 200, "{\"result\":[]}");
        http.stubJson("https://api.cloudflare.com/client/v4/zones?name=example.com", 200, "{\"result\":[]}");

        CloudflareDnsProvider provider = new CloudflareDnsProvider(http, "token");
        assertThatThrownBy(() -> provider.createTxtRecord("vpn.example.com", "digest"))
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("no Cloudflare zone found");
    }

    @Test
    void createTxtRecord_cloudflareRejectsRequest_throwsWithDetail() {
        http.stubJson("https://api.cloudflare.com/client/v4/zones?name=example.com", 200, """
                {"result":[{"id":"zone-x"}]}
                """);
        http.postBodyStub("https://api.cloudflare.com/client/v4/zones/zone-x/dns_records", 403, """
                {"success":false,"errors":[{"code":9109,"message":"Invalid access token"}]}
                """, Map.of("content-type", "application/json"));

        CloudflareDnsProvider provider = new CloudflareDnsProvider(http, "bad-token");
        assertThatThrownBy(() -> provider.createTxtRecord("example.com", "digest"))
                .isInstanceOf(AcmeException.class)
                .hasMessageContaining("createTxtRecord failed");
    }

    @Test
    void deleteTxtRecord_sendsDeleteToTheRecordCreated() throws Exception {
        http.deleteStub("https://api.cloudflare.com/client/v4/zones/zone-x/dns_records/rec-x", 200, """
                {"success":true}
                """, Map.of("content-type", "application/json"));

        CloudflareDnsProvider provider = new CloudflareDnsProvider(http, "token");
        provider.deleteTxtRecord("example.com", "zone-x:rec-x");

        boolean deleted = FakeHttpFetcher.calls.stream().anyMatch(c ->
                "DELETE".equals(c.method())
                        && "https://api.cloudflare.com/client/v4/zones/zone-x/dns_records/rec-x".equals(c.url())
                        && "Bearer token".equals(c.headers().get("Authorization")));
        assertThat(deleted).isTrue();
    }
}
