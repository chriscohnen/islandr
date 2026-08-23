package de.chriscohnen.islandr.identity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CRUD, preset-templating, discovery-on-save, and mutual exclusion for
 * {@link OidcCustomProviderService} — issue #69. Mutual exclusion is
 * specifically tested *across* {@link OidcProvider} (MS365/Google) and
 * {@link OidcCustomProvider}, the actual regression risk this feature adds:
 * enabling a custom provider must disable an active MS365/Google, and vice
 * versa, not just other custom providers.
 */
@QuarkusTest
class OidcCustomProviderServiceTest {

    @Inject OidcCustomProviderService customProviders;
    @Inject OidcProviderService fixedProviders;
    @Inject FakeHttpFetcher http;

    @BeforeEach
    void reset() {
        http.reset();
        disableFixed("microsoft");
        disableFixed("google");
        for (OidcCustomProvider p : allCustom()) disableAndDeleteCustom(p.id);
        stubDiscovery("https://tenant-a.example.com");
        stubDiscovery("https://my-org.okta.com/oauth2/default");
        stubDiscovery("https://my-tenant.auth0.com/");
    }

    @Test
    void create_genericIssuer_discoversAndPersistsDisabled() {
        OidcCustomProvider p = customProviders.create(
                new OidcCustomProviderDto.CreateRequest(null, null, "https://tenant-a.example.com",
                        "Keycloak", "client-1", "secret-1", ""), "admin");

        assertThat(p.enabled).isFalse();
        assertThat(p.isDiscovered()).isTrue();
        assertThat(p.authorizeEndpoint).isEqualTo("https://tenant-a.example.com/authorize");
    }

    @Test
    void create_oktaPreset_templatesIssuerFromDomain() {
        OidcCustomProvider p = customProviders.create(
                new OidcCustomProviderDto.CreateRequest("okta", "my-org.okta.com", null,
                        "Okta", "client-2", "secret-2", ""), "admin");

        assertThat(p.issuerUrl).isEqualTo("https://my-org.okta.com/oauth2/default");
        assertThat(p.preset).isEqualTo("okta");
    }

    @Test
    void create_auth0Preset_templatesIssuerFromDomain() {
        OidcCustomProvider p = customProviders.create(
                new OidcCustomProviderDto.CreateRequest("auth0", "my-tenant.auth0.com", null,
                        "Auth0", "client-3", "secret-3", ""), "admin");

        assertThat(p.issuerUrl).isEqualTo("https://my-tenant.auth0.com/");
    }

    @Test
    void create_auth0Preset_stripsAccidentalSchemeFromDomainInput() {
        OidcCustomProvider p = customProviders.create(
                new OidcCustomProviderDto.CreateRequest("auth0", "https://my-tenant.auth0.com/some/path", null,
                        "Auth0", "client-4", "secret-4", ""), "admin");

        assertThat(p.issuerUrl).isEqualTo("https://my-tenant.auth0.com/");
    }

    @Test
    void create_presetWithoutDomain_rejected() {
        assertThatThrownBy(() -> customProviders.create(
                new OidcCustomProviderDto.CreateRequest("okta", null, null, "Okta", "c", "s", ""), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("domain is required");
    }

    @Test
    void create_noPresetNoIssuerUrl_rejected() {
        assertThatThrownBy(() -> customProviders.create(
                new OidcCustomProviderDto.CreateRequest(null, null, null, "Whatever", "c", "s", ""), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("issuerUrl is required");
    }

    @Test
    void create_discoveryFails_nothingPersisted() {
        // No stub for this host — discovery 404s.
        assertThatThrownBy(() -> customProviders.create(
                new OidcCustomProviderDto.CreateRequest(null, null, "https://unreachable.example.com",
                        "Broken", "c", "s", ""), "admin"))
                .isInstanceOf(BadRequestException.class);

        assertThat(allCustom()).isEmpty();
    }

    @Test
    void enable_requiresCredentials() {
        OidcCustomProvider p = createGeneric();
        assertThatThrownBy(() -> customProviders.update(p.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, null, null, null, true), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void enable_customProvider_disablesActiveMicrosoft() {
        enableFixed("microsoft", "ms-client", "ms-secret", "tenant-x", null);
        OidcCustomProvider p = createGeneric();

        OidcCustomProviderService.UpdateResult result = customProviders.update(p.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, "cid", "csecret", null, true), "admin");

        assertThat(result.provider().enabled).isTrue();
        assertThat(result.deactivatedOthers()).containsExactly("microsoft");
        assertThat(OidcProvider.<OidcProvider>findById("microsoft").enabled).isFalse();
    }

    @Test
    void enable_microsoft_disablesActiveCustomProvider() {
        OidcCustomProvider p = createGeneric();
        customProviders.update(p.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, "cid", "csecret", null, true), "admin");

        OidcProviderService.UpdateResult result = fixedProviders.update("microsoft",
                new OidcProviderDto.UpdateRequest(true, "ms-client", "ms-secret", "tenant-x", null), "admin");

        assertThat(result.deactivatedOthers()).containsExactly(p.id);
        assertThat(OidcCustomProvider.<OidcCustomProvider>findById(p.id).enabled).isFalse();
    }

    @Test
    void enable_twoCustomProviders_secondDisablesFirst() {
        OidcCustomProvider a = createGeneric();
        customProviders.update(a.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, "cid-a", "sec-a", null, true), "admin");

        OidcCustomProvider b = customProviders.create(
                new OidcCustomProviderDto.CreateRequest("okta", "my-org.okta.com", null,
                        "Okta", "cid-b", "sec-b", ""), "admin");
        OidcCustomProviderService.UpdateResult result = customProviders.update(b.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, null, null, null, true), "admin");

        assertThat(result.deactivatedOthers()).containsExactly(a.id);
        assertThat(OidcCustomProvider.<OidcCustomProvider>findById(a.id).enabled).isFalse();
        assertThat(OidcCustomProvider.<OidcCustomProvider>findById(b.id).enabled).isTrue();
    }

    @Test
    void update_changedIssuerUrl_rerunsDiscovery() {
        OidcCustomProvider p = createGeneric();
        stubDiscovery("https://tenant-b.example.com");

        OidcCustomProviderService.UpdateResult result = customProviders.update(p.id,
                new OidcCustomProviderDto.UpdateRequest(null, "https://tenant-b.example.com", null, null, null, null),
                "admin");

        assertThat(result.provider().authorizeEndpoint).isEqualTo("https://tenant-b.example.com/authorize");
    }

    @Test
    void delete_whileEnabled_rejected() {
        OidcCustomProvider p = createGeneric();
        customProviders.update(p.id,
                new OidcCustomProviderDto.UpdateRequest(null, null, "cid", "csecret", null, true), "admin");

        assertThatThrownBy(() -> customProviders.delete(p.id))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("disable");
    }

    @Test
    void delete_whileDisabled_succeeds() {
        OidcCustomProvider p = createGeneric();
        customProviders.delete(p.id);
        assertThat(OidcCustomProvider.<OidcCustomProvider>findById(p.id)).isNull();
    }

    // -- helpers --------------------------------------------------------

    private OidcCustomProvider createGeneric() {
        return customProviders.create(
                new OidcCustomProviderDto.CreateRequest(null, null, "https://tenant-a.example.com",
                        "Keycloak", null, null, ""), "admin");
    }

    private void stubDiscovery(String issuer) {
        http.stubJson(issuer.replaceAll("/$", "") + "/.well-known/openid-configuration", 200, """
            {"issuer":"%s",
             "authorization_endpoint":"%s/authorize",
             "token_endpoint":"%s/token",
             "jwks_uri":"%s/jwks"}
            """.formatted(issuer, stripSlash(issuer), stripSlash(issuer), stripSlash(issuer)));
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Transactional
    void enableFixed(String key, String clientId, String secret, String tenantId, String domains) {
        fixedProviders.update(key, new OidcProviderDto.UpdateRequest(true, clientId, secret, tenantId, domains), "admin");
    }

    @Transactional
    void disableFixed(String key) {
        fixedProviders.update(key, new OidcProviderDto.UpdateRequest(false, null, null, null, null), "admin");
    }

    @Transactional
    java.util.List<OidcCustomProvider> allCustom() {
        return customProviders.listAll();
    }

    @Transactional
    void disableAndDeleteCustom(String id) {
        OidcCustomProvider p = OidcCustomProvider.findById(id);
        if (p != null) {
            p.enabled = false;
            p.delete();
        }
    }
}
