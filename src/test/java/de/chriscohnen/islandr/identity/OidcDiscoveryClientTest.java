package de.chriscohnen.islandr.identity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests {@link OidcDiscoveryClient} against a fake HTTP layer — no real
 *  network, no real Okta/Auth0/Keycloak instance (issue #69). */
@QuarkusTest
class OidcDiscoveryClientTest {

    @Inject OidcDiscoveryClient discovery;
    @Inject FakeHttpFetcher http;

    @BeforeEach
    void reset() {
        http.reset();
    }

    @Test
    void discover_wellFormedDocument_returnsEndpoints() {
        http.stubJson("https://tenant.example.okta.com/.well-known/openid-configuration", 200, """
            {"issuer":"https://tenant.example.okta.com",
             "authorization_endpoint":"https://tenant.example.okta.com/authorize",
             "token_endpoint":"https://tenant.example.okta.com/token",
             "jwks_uri":"https://tenant.example.okta.com/jwks",
             "userinfo_endpoint":"https://tenant.example.okta.com/userinfo"}
            """);

        OidcDiscoveryClient.Discovered d = discovery.discover("https://tenant.example.okta.com");

        assertThat(d.issuer()).isEqualTo("https://tenant.example.okta.com");
        assertThat(d.authorizationEndpoint()).isEqualTo("https://tenant.example.okta.com/authorize");
        assertThat(d.tokenEndpoint()).isEqualTo("https://tenant.example.okta.com/token");
        assertThat(d.jwksUri()).isEqualTo("https://tenant.example.okta.com/jwks");
        assertThat(d.userinfoEndpoint()).isEqualTo("https://tenant.example.okta.com/userinfo");
    }

    @Test
    void discover_trailingSlashOnIssuerUrlButNotInDocument_stillMatches() {
        http.stubJson("https://tenant.example.okta.com/.well-known/openid-configuration", 200, """
            {"issuer":"https://tenant.example.okta.com",
             "authorization_endpoint":"https://tenant.example.okta.com/authorize",
             "token_endpoint":"https://tenant.example.okta.com/token",
             "jwks_uri":"https://tenant.example.okta.com/jwks"}
            """);

        OidcDiscoveryClient.Discovered d = discovery.discover("https://tenant.example.okta.com/");

        assertThat(d.issuer()).isEqualTo("https://tenant.example.okta.com");
    }

    @Test
    void discover_nonHttpsIssuer_rejected() {
        assertThatThrownBy(() -> discovery.discover("http://tenant.example.okta.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void discover_blankIssuer_rejected() {
        assertThatThrownBy(() -> discovery.discover("  "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void discover_unreachableEndpoint_rejected() {
        // Nothing stubbed for this URL — FakeHttpFetcher returns a 404.
        assertThatThrownBy(() -> discovery.discover("https://nowhere.example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void discover_missingRequiredField_rejected() {
        http.stubJson("https://broken.example.com/.well-known/openid-configuration", 200, """
            {"issuer":"https://broken.example.com","authorization_endpoint":"https://broken.example.com/authorize"}
            """); // token_endpoint / jwks_uri missing

        assertThatThrownBy(() -> discovery.discover("https://broken.example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("missing a required field");
    }

    @Test
    void discover_malformedJson_rejected() {
        http.stub("https://malformed.example.com/.well-known/openid-configuration",
                200, "not json at all".getBytes(), java.util.Map.of("content-type", "application/json"));

        assertThatThrownBy(() -> discovery.discover("https://malformed.example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void discover_issuerMismatch_rejected() {
        // Document claims to be a *different* issuer than what the admin
        // entered — defends against a typo'd issuer or an unexpected redirect.
        http.stubJson("https://typo.example.com/.well-known/openid-configuration", 200, """
            {"issuer":"https://attacker.example.com",
             "authorization_endpoint":"https://attacker.example.com/authorize",
             "token_endpoint":"https://attacker.example.com/token",
             "jwks_uri":"https://attacker.example.com/jwks"}
            """);

        assertThatThrownBy(() -> discovery.discover("https://typo.example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not match");
    }
}
