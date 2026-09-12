package de.chriscohnen.islandr.identity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #81: setting up Entra ID means copying four values, and until this
 * check existed the first wrong one surfaced as a login error naming neither
 * the field nor the cause. Each test pins one wrong value and asserts that the
 * report blames that field — and only that field.
 *
 * <p>Plain JUnit with a hand-written fetcher rather than {@code @QuarkusTest}:
 * the class is pure request/response logic, and the shape of Entra's answers
 * is the entire subject.
 */
class MicrosoftConfigCheckTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT = "22222222-2222-2222-2222-222222222222";
    private static final String REDIRECT = "https://hub.firma.de/api/v1/auth/oidc/microsoft/callback";

    private static final String DISCOVERY =
            "https://login.microsoftonline.com/" + TENANT + "/v2.0/.well-known/openid-configuration";

    /** Answers by URL prefix, so the long authorize URL need not be rebuilt here. */
    private static final class Fetcher implements HttpFetcher {
        final List<String> seen = new ArrayList<>();
        Function<String, Response> onGet = url -> new Response(404, new byte[0], Map.of());
        Function<String, Response> onPost = url -> new Response(404, new byte[0], Map.of());

        @Override public Response get(String url, Map<String, String> h) { seen.add(url); return onGet.apply(url); }
        @Override public Response postForm(String url, Map<String, String> f, Map<String, String> h) {
            seen.add(url); return onPost.apply(url);
        }
        @Override public Response postBody(String url, byte[] b, String ct, Map<String, String> h) {
            throw new UnsupportedOperationException();
        }
        @Override public Response delete(String url, Map<String, String> h) {
            throw new UnsupportedOperationException();
        }
    }

    private static HttpFetcher.Response ok(String body) {
        return new HttpFetcher.Response(200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
    }

    private static HttpFetcher.Response fail(int status, String body) {
        return new HttpFetcher.Response(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
    }

    private static OidcProvider provider() {
        OidcProvider p = new OidcProvider();
        p.providerKey = OidcProvider.MICROSOFT;
        p.tenantId = TENANT;
        p.clientId = CLIENT;
        p.clientSecret = "a-real-looking-secret-value";
        return p;
    }

    private MicrosoftConfigCheck checkWith(Fetcher f) {
        MicrosoftConfigCheck c = new MicrosoftConfigCheck();
        c.http = f;
        return c;
    }

    private static MicrosoftConfigCheck.Check field(MicrosoftConfigCheck.Report r, String name) {
        return r.checks().stream().filter(c -> c.field().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everythingCorrect_reportsOkForEveryField() {
        Fetcher f = new Fetcher();
        f.onGet = url -> ok("{}");                       // discovery + the authorize sign-in page
        f.onPost = url -> ok("{\"access_token\":\"x\"}"); // client_credentials accepted
        MicrosoftConfigCheck.Report r = checkWith(f).run(provider(), REDIRECT);

        assertThat(r.ok()).isTrue();
        assertThat(r.checks()).extracting(MicrosoftConfigCheck.Check::status)
                .containsOnly(MicrosoftConfigCheck.OK);
        assertThat(r.redirectUri()).isEqualTo(REDIRECT);
    }

    /** A tenant that does not resolve blames the tenant and nothing else. */
    @Test
    void unknownTenant_blamesTheTenantAndSkipsTheRest() {
        Fetcher f = new Fetcher();
        f.onGet = url -> url.equals(DISCOVERY)
                ? fail(400, "{\"error\":\"invalid_tenant\",\"error_description\":\"AADSTS900023\"}")
                : ok("{}");
        MicrosoftConfigCheck.Report r = checkWith(f).run(provider(), REDIRECT);

        assertThat(r.ok()).isFalse();
        assertThat(field(r, "tenantId").status()).isEqualTo(MicrosoftConfigCheck.FAILED);
        assertThat(field(r, "clientId").status()).isEqualTo(MicrosoftConfigCheck.SKIPPED);
        assertThat(field(r, "clientSecret").status()).isEqualTo(MicrosoftConfigCheck.SKIPPED);
        assertThat(field(r, "redirectUri").status()).isEqualTo(MicrosoftConfigCheck.SKIPPED);
    }

    @Test
    void unknownApplication_blamesTheClientId() {
        Fetcher f = new Fetcher();
        f.onGet = url -> url.equals(DISCOVERY) ? ok("{}")
                : ok("<html>AADSTS700016: Application with identifier was not found</html>");
        f.onPost = url -> ok("{\"access_token\":\"x\"}");
        MicrosoftConfigCheck.Report r = checkWith(f).run(provider(), REDIRECT);

        assertThat(field(r, "tenantId").status()).isEqualTo(MicrosoftConfigCheck.OK);
        MicrosoftConfigCheck.Check client = field(r, "clientId");
        assertThat(client.status()).isEqualTo(MicrosoftConfigCheck.FAILED);
        assertThat(client.code()).isEqualTo("AADSTS700016");
    }

    /** "Wrong" and "expired" demand different actions and must not look alike. */
    @Test
    void wrongSecret_isDistinguishedFromExpiredSecret() {
        Fetcher wrong = new Fetcher();
        wrong.onGet = url -> ok("{}");
        wrong.onPost = url -> fail(401, "{\"error_description\":\"AADSTS7000215: Invalid client secret provided.\"}");
        MicrosoftConfigCheck.Check c1 = field(checkWith(wrong).run(provider(), REDIRECT), "clientSecret");
        assertThat(c1.status()).isEqualTo(MicrosoftConfigCheck.FAILED);
        assertThat(c1.code()).isEqualTo("AADSTS7000215");
        assertThat(c1.message()).contains("Value");

        Fetcher expired = new Fetcher();
        expired.onGet = url -> ok("{}");
        expired.onPost = url -> fail(401, "{\"error_description\":\"AADSTS7000222: The provided client secret keys are expired.\"}");
        MicrosoftConfigCheck.Check c2 = field(checkWith(expired).run(provider(), REDIRECT), "clientSecret");
        assertThat(c2.status()).isEqualTo(MicrosoftConfigCheck.FAILED);
        assertThat(c2.code()).isEqualTo("AADSTS7000222");
        assertThat(c2.message()).contains("expired");
        assertThat(c2.message()).isNotEqualTo(c1.message());
    }

    @Test
    void unregisteredRedirectUri_blamesTheRedirectUriAndShowsIt() {
        Fetcher f = new Fetcher();
        f.onGet = url -> url.equals(DISCOVERY) ? ok("{}")
                : ok("<html>AADSTS50011: The redirect URI specified in the request does not match</html>");
        f.onPost = url -> ok("{\"access_token\":\"x\"}");
        MicrosoftConfigCheck.Report r = checkWith(f).run(provider(), REDIRECT);

        // AADSTS50011 is not 700016, so the client ID itself is fine.
        assertThat(field(r, "clientId").status()).isEqualTo(MicrosoftConfigCheck.OK);
        MicrosoftConfigCheck.Check redirect = field(r, "redirectUri");
        assertThat(redirect.status()).isEqualTo(MicrosoftConfigCheck.FAILED);
        assertThat(redirect.code()).isEqualTo("AADSTS50011");
        assertThat(redirect.message()).contains(REDIRECT);
    }

    @Test
    void secretIdShape_isRecognisedWithoutAnyNetworkCall() {
        assertThat(MicrosoftConfigCheck.looksLikeSecretId("11111111-2222-3333-4444-555555555555")).isTrue();
        assertThat(MicrosoftConfigCheck.looksLikeSecretId("  11111111-2222-3333-4444-555555555555 ")).isTrue();
        // Real secret Values are opaque strings with punctuation, never UUIDs.
        assertThat(MicrosoftConfigCheck.looksLikeSecretId("8Xt~Q3.bK2lR-9Vd_uW1yZaB4cE5fG6h")).isFalse();
        assertThat(MicrosoftConfigCheck.looksLikeSecretId(null)).isFalse();
    }
}
