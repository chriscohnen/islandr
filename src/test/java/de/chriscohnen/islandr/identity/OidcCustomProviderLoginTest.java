package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end login round trip through a generic OIDC provider (issue #69) —
 * the same {@link OidcLoginService} path MS365/Google use, just with a
 * discovered-not-hardcoded provider. Confirms the {@link OidcProviderRegistry}
 * dispatch and the (kind='custom', customProviderId) user/session persistence
 * scheme actually work end-to-end, not just in isolated unit tests.
 */
@QuarkusTest
class OidcCustomProviderLoginTest {

    private static final String ISSUER = "https://tenant-login.example.com";
    private static final String CLIENT_ID = "custom-client-id";

    @Inject OidcLoginService oidc;
    @Inject OidcCustomProviderService customProviders;
    @Inject FakeHttpFetcher http;
    @Inject de.chriscohnen.islandr.settings.SettingsService settingsSvc;

    private TestJwt jwt;
    private String providerId;

    @BeforeEach
    void setUp() {
        http.reset();
        jwt = new TestJwt("custom-test-kid-" + System.identityHashCode(this));
        http.stubJson(ISSUER + "/.well-known/openid-configuration", 200, """
            {"issuer":"%s",
             "authorization_endpoint":"%s/authorize",
             "token_endpoint":"%s/token",
             "jwks_uri":"%s/jwks"}
            """.formatted(ISSUER, ISSUER, ISSUER, ISSUER));
        http.stubJson(ISSUER + "/jwks", 200, jwt.jwksJson());
        ensureAutoProvisionEnabled();
        providerId = createAndEnable();
    }

    @Test
    void callback_happyPath_createsUserBoundToThisCustomProvider() {
        String idToken = jwt.signIdToken(TestJwt.genericClaims(
                ISSUER, CLIENT_ID, "sub-1", "frank@firma.de", "Frank"));
        stubTokenExchange(idToken, "access-token-1");

        Session s = oidc.handleCallback(providerId, "code-1", "st", "st",
                "http://localhost:8080/api/v1/auth/oidc/" + providerId + "/callback");

        assertThat(s.provider).isEqualTo("custom");
        assertThat(s.oidcCustomProviderId).isEqualTo(providerId);

        User u = readUser(s.userId);
        assertThat(u.email).isEqualTo("frank@firma.de");
        assertThat(u.oidcProvider).isEqualTo("custom");
        assertThat(u.oidcSubject).isEqualTo("sub-1");
        assertThat(u.oidcCustomProviderId).isEqualTo(providerId);
    }

    @Test
    void callback_secondLoginSameSubject_reusesSameUser() {
        String idToken = jwt.signIdToken(TestJwt.genericClaims(
                ISSUER, CLIENT_ID, "sub-2", "gina@firma.de", "Gina"));
        stubTokenExchange(idToken, "access-token-2");
        String redirect = "http://localhost:8080/api/v1/auth/oidc/" + providerId + "/callback";

        Session first = oidc.handleCallback(providerId, "code-a", "st", "st", redirect);
        Session second = oidc.handleCallback(providerId, "code-b", "st2", "st2", redirect);

        assertThat(second.userId).isEqualTo(first.userId);
    }

    @Test
    void callback_wrongIssuerInToken_rejected() {
        // Token signed as if from a *different* issuer than the one this
        // provider discovered — must fail closed, not silently accept it.
        String idToken = jwt.signIdToken(TestJwt.genericClaims(
                "https://someone-else.example.com", CLIENT_ID, "sub-3", "hank@firma.de", "Hank"));
        stubTokenExchange(idToken, "access-token-3");

        assertThatThrownBy(() -> oidc.handleCallback(providerId, "code-3", "st", "st",
                "http://localhost:8080/api/v1/auth/oidc/" + providerId + "/callback"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unexpected iss");
    }

    @Test
    void callback_disabledCustomProvider_rejected() {
        disable(providerId);
        assertThatThrownBy(() -> oidc.handleCallback(providerId, "c", "s", "s",
                "http://localhost:8080/api/v1/auth/oidc/" + providerId + "/callback"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void buildAuthorizeUrl_usesDiscoveredAuthorizeEndpoint() {
        OidcLoginService.AuthorizeRedirect a = oidc.buildAuthorizeUrl(providerId,
                "http://localhost:8080/api/v1/auth/oidc/" + providerId + "/callback");
        assertThat(a.url())
                .startsWith(ISSUER + "/authorize?")
                .contains("client_id=" + CLIENT_ID)
                .contains("scope=openid+profile+email");
    }

    // -- helpers --------------------------------------------------------

    private String createAndEnable() {
        OidcCustomProvider p = customProviders.create(
                new OidcCustomProviderDto.CreateRequest(null, null, ISSUER,
                        "Test IdP", CLIENT_ID, "custom-secret", "firma.de"), "test");
        customProviders.update(p.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, null, null, null, true), "test");
        return p.id;
    }

    @Transactional
    void disable(String id) {
        customProviders.update(id,
                new OidcCustomProviderDto.UpdateRequest(null, null, null, null, null, false), "test");
    }

    @Transactional
    void ensureAutoProvisionEnabled() {
        settingsSvc.get().oidcAutoProvision = true;
    }

    @Transactional
    User readUser(String id) {
        return User.findById(id);
    }

    private void stubTokenExchange(String idToken, String accessToken) {
        try {
            String body = new ObjectMapper().writeValueAsString(
                    Map.of("id_token", idToken, "access_token", accessToken, "token_type", "Bearer"));
            http.postFormStub(ISSUER + "/token", 200, body, null);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
