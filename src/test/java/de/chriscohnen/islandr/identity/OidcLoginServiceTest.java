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

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the OIDC code-exchange + verify + user-upsert + avatar-cache pipeline.
 * The HTTP layer is replaced by FakeHttpFetcher; signing keys come from TestJwt.
 */
@QuarkusTest
class OidcLoginServiceTest {

    static final String TENANT = "11111111-2222-3333-4444-555555555555";
    static final String MS_CLIENT = "ms-client-id";
    static final String GOOGLE_CLIENT = "google-client-id";
    static final String REDIRECT_URI = "http://localhost:8080/api/v1/auth/oidc/microsoft/callback";

    @Inject OidcLoginService oidc;
    @Inject FakeHttpFetcher http;
    @Inject OidcProviderService providers;
    @Inject de.chriscohnen.islandr.settings.SettingsService settingsSvc;

    private static int kidCounter;
    private TestJwt msJwt;
    private TestJwt googleJwt;

    @BeforeEach
    void resetAndConfigureProviders() {
        http.reset();
        // Unique kids per test — the JwksCache is app-scoped and keyed by (url, kid),
        // so reusing the same kid across tests would let an earlier test's public key
        // verify a later test's token signed with a different private key.
        kidCounter++;
        msJwt = new TestJwt("ms-test-kid-" + kidCounter);
        googleJwt = new TestJwt("google-test-kid-" + kidCounter);
        // Reset enabled state explicitly — singleton DB state survives between
        // tests and the new mutual-exclusion rule means activating one disables
        // the other, so we can't rely on activation order.
        disable("microsoft");
        disable("google");
        ensureAutoProvisionEnabled();
        configureMicrosoft();
        configureGoogle();
        http.stubJson(
                "https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys",
                200, msJwt.jwksJson());
        http.stubJson(
                "https://www.googleapis.com/oauth2/v3/certs",
                200, googleJwt.jwksJson());
    }

    @Test
    void callback_microsoft_happyPath_createsUserAndCachesGraphPhoto() {
        enable("microsoft");
        String idToken = msJwt.signIdToken(TestJwt.microsoftClaims(
                TENANT, MS_CLIENT, "ms-sub-1", "alice@firma.de", "Alice"));
        stubTokenExchange("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token",
                idToken, "ms-access-token");
        http.stub("https://graph.microsoft.com/v1.0/me/photo/$value",
                200, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 },
                Map.of("content-type", "image/jpeg"));

        String state = "state-xyz";
        Session s = oidc.handleCallback("microsoft", "code-abc", state, state, REDIRECT_URI);

        assertThat(s.id).isNotBlank();
        assertThat(s.provider).isEqualTo("microsoft");
        assertThat(s.expiresAt).isAfter(Instant.now());

        User u = readUser(s.userId);
        assertThat(u).isNotNull();
        assertThat(u.email).isEqualTo("alice@firma.de");
        assertThat(u.oidcProvider).isEqualTo("microsoft");
        assertThat(u.oidcSubject).isEqualTo("ms-sub-1");
        assertThat(u.avatarBytes).hasSize(6);
        assertThat(u.avatarContentType).isEqualTo("image/jpeg");
    }

    @Test
    void callback_microsoft_noPhotoFromGraph_userStillCreated() {
        enable("microsoft");
        String idToken = msJwt.signIdToken(TestJwt.microsoftClaims(
                TENANT, MS_CLIENT, "ms-sub-2", "bob@firma.de", "Bob"));
        stubTokenExchange("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token",
                idToken, "ms-access-token");
        http.stub("https://graph.microsoft.com/v1.0/me/photo/$value", 404, new byte[0], null);

        Session s = oidc.handleCallback("microsoft", "code-2", "st", "st", REDIRECT_URI);
        User u = readUser(s.userId);
        assertThat(u.avatarBytes).isNull();
    }

    @Test
    void callback_google_happyPath_fetchesPictureFromUrl() {
        enable("google");
        String pictureUrl = "https://lh3.googleusercontent.com/a/photo123";
        String idToken = googleJwt.signIdToken(TestJwt.googleClaims(
                GOOGLE_CLIENT, "google-sub-1", "carol@firma.de", "Carol", pictureUrl));
        stubTokenExchange("https://oauth2.googleapis.com/token", idToken, "g-access-token");
        http.stub(pictureUrl, 200, new byte[] { 1, 2, 3, 4 }, Map.of("content-type", "image/png"));

        Session s = oidc.handleCallback("google", "code-g1", "st", "st",
                "http://localhost:8080/api/v1/auth/oidc/google/callback");

        User u = readUser(s.userId);
        assertThat(u.oidcProvider).isEqualTo("google");
        assertThat(u.avatarContentType).isEqualTo("image/png");
        assertThat(u.avatarBytes).hasSize(4);
    }

    @Test
    void callback_google_gravatarEnabled_prefersGravatarOverIdpPicture() {
        // With the gravatar toggle on, an OIDC user with a Gravatar should
        // get the Gravatar bytes (not the Google picture). Users curate
        // Gravatar deliberately; the Workspace photo is often stale.
        setGravatarEnabled(true);
        enable("google");
        String pictureUrl = "https://lh3.googleusercontent.com/a/photo-google";
        String idToken = googleJwt.signIdToken(TestJwt.googleClaims(
                GOOGLE_CLIENT, "google-sub-grav", "dave@firma.de", "Dave", pictureUrl));
        stubTokenExchange("https://oauth2.googleapis.com/token", idToken, "g-access-token");
        // Google's picture would be 4 bytes; Gravatar's is 9 bytes. We
        // assert on the size so we know which source wrote the cache.
        http.stub(pictureUrl, 200, new byte[] { 1, 2, 3, 4 }, Map.of("content-type", "image/png"));
        // md5("dave@firma.de") — Gravatar URL the AvatarFetcher will call.
        String gravatarUrl = "https://www.gravatar.com/avatar/"
                + de.chriscohnen.islandr.identity.AvatarFetcher.md5Hex("dave@firma.de") + "?s=200&d=404";
        http.stub(gravatarUrl, 200, new byte[] { 9, 9, 9, 9, 9, 9, 9, 9, 9 },
                Map.of("content-type", "image/jpeg"));

        Session s = oidc.handleCallback("google", "code-grav", "st", "st",
                "http://localhost:8080/api/v1/auth/oidc/google/callback");

        User u = readUser(s.userId);
        assertThat(u.avatarBytes).hasSize(9);
        assertThat(u.avatarSource).isEqualTo("gravatar");
    }

    @Test
    void callback_google_gravatarEnabledButNoGravatar_fallsBackToIdpPicture() {
        // Toggle on but Gravatar returns 404 → OIDC picture should still cache.
        setGravatarEnabled(true);
        enable("google");
        String pictureUrl = "https://lh3.googleusercontent.com/a/photo-fallback";
        String idToken = googleJwt.signIdToken(TestJwt.googleClaims(
                GOOGLE_CLIENT, "google-sub-fb", "erin@firma.de", "Erin", pictureUrl));
        stubTokenExchange("https://oauth2.googleapis.com/token", idToken, "g-access-token");
        http.stub(pictureUrl, 200, new byte[] { 1, 2, 3, 4 }, Map.of("content-type", "image/png"));
        String gravatarUrl = "https://www.gravatar.com/avatar/"
                + de.chriscohnen.islandr.identity.AvatarFetcher.md5Hex("erin@firma.de") + "?s=200&d=404";
        http.stub(gravatarUrl, 404, new byte[0], null);

        Session s = oidc.handleCallback("google", "code-fb", "st", "st",
                "http://localhost:8080/api/v1/auth/oidc/google/callback");

        User u = readUser(s.userId);
        assertThat(u.avatarBytes).hasSize(4);
        assertThat(u.avatarSource).isEqualTo("oidc");
    }

    @Test
    void callback_emailDomainNotAllowed_rejected() {
        enable("microsoft");
        String idToken = msJwt.signIdToken(TestJwt.microsoftClaims(
                TENANT, MS_CLIENT, "ms-sub-bad", "eve@evil.com", "Eve"));
        stubTokenExchange("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token",
                idToken, "ms-access-token");

        assertThatThrownBy(() -> oidc.handleCallback("microsoft", "c", "s", "s", REDIRECT_URI))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void callback_emptyAllowlist_acceptsAnyDomain() {
        // Family-Gmail style: operator leaves allowedDomains blank and relies
        // on the Google consent screen (External + test-users) to gate logins.
        // Backend must NOT add an extra email-domain filter in that case.
        clearMicrosoftAllowlist();
        enable("microsoft");
        String idToken = msJwt.signIdToken(TestJwt.microsoftClaims(
                TENANT, MS_CLIENT, "ms-sub-anyone", "anyone@whatever.example", "Anyone"));
        stubTokenExchange("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token",
                idToken, "ms-access-token");
        http.stub("https://graph.microsoft.com/v1.0/me/photo/$value", 404, new byte[0], null);

        Session s = oidc.handleCallback("microsoft", "c", "s", "s", REDIRECT_URI);
        User u = readUser(s.userId);
        assertThat(u.email).isEqualTo("anyone@whatever.example");
    }

    @Test
    void callback_stateMismatch_rejected() {
        assertThatThrownBy(() -> oidc.handleCallback("microsoft", "c", "state-a", "state-b", REDIRECT_URI))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("state mismatch");
    }

    @Test
    void callback_disabledProvider_rejected() {
        // BeforeEach configures credentials but leaves microsoft disabled.
        assertThatThrownBy(() -> oidc.handleCallback("microsoft", "c", "s", "s", REDIRECT_URI))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void buildAuthorizeUrl_includesRequiredParams() {
        enable("microsoft");
        OidcLoginService.AuthorizeRedirect a = oidc.buildAuthorizeUrl("microsoft", REDIRECT_URI);
        assertThat(a.url())
                .startsWith("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/authorize?")
                .contains("client_id=" + MS_CLIENT)
                .contains("response_type=code")
                .contains("state=")
                .contains("scope=");
        assertThat(a.state()).isNotBlank();
    }

    @Test
    void callback_existingLocalUserSameEmail_linkedToOidc() {
        enable("microsoft");
        createLocalUser("Dan Local", "dan@firma.de");
        String idToken = msJwt.signIdToken(TestJwt.microsoftClaims(
                TENANT, MS_CLIENT, "ms-sub-dan", "dan@firma.de", "Dan Microsoft"));
        stubTokenExchange("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token",
                idToken, "ms-access-token");
        http.stub("https://graph.microsoft.com/v1.0/me/photo/$value", 404, new byte[0], null);

        Session s = oidc.handleCallback("microsoft", "c", "s", "s", REDIRECT_URI);
        User u = readUser(s.userId);
        assertThat(u.oidcSubject).isEqualTo("ms-sub-dan");
        assertThat(u.name).isEqualTo("Dan Microsoft");  // refreshed from token
    }

    // -- helpers --------------------------------------------------------------

    // Provider-Credentials werden im BeforeEach gesetzt, aber NICHT aktiviert.
    // Tests aktivieren genau den Provider, den sie brauchen — das Backend
    // erzwingt jetzt mutual exclusion (nur einer enabled=true gleichzeitig).
    @Transactional
    void configureMicrosoft() {
        providers.update("microsoft", new OidcProviderDto.UpdateRequest(
                null, MS_CLIENT, "ms-secret", TENANT, "firma.de"), "test");
    }

    @Transactional
    void clearMicrosoftAllowlist() {
        // Empty string in the DTO is treated as "set to empty" by OidcProviderService
        // (normaliseDomains turns blank into null). That's the family-Gmail setup.
        providers.update("microsoft", new OidcProviderDto.UpdateRequest(
                null, null, null, null, ""), "test");
    }

    @Transactional
    void configureGoogle() {
        providers.update("google", new OidcProviderDto.UpdateRequest(
                null, GOOGLE_CLIENT, "g-secret", null, "firma.de"), "test");
    }

    @Transactional
    void enable(String key) {
        providers.update(key, new OidcProviderDto.UpdateRequest(
                true, null, null, null, null), "test");
    }

    @Transactional
    void disable(String key) {
        providers.update(key, new OidcProviderDto.UpdateRequest(
                false, null, null, null, null), "test");
    }

    @Transactional
    void ensureAutoProvisionEnabled() {
        settingsSvc.get().oidcAutoProvision = true;
    }

    @Transactional
    void setGravatarEnabled(boolean enabled) {
        // Bypass the DTO (which would force re-validating all WG fields)
        // and tweak the singleton row directly.
        de.chriscohnen.islandr.settings.Settings s = settingsSvc.get();
        s.gravatarEnabled = enabled;
    }

    @Transactional
    void disableMicrosoft() {
        providers.update("microsoft", new OidcProviderDto.UpdateRequest(
                false, null, null, null, null), "test");
    }

    @Transactional
    void createLocalUser(String name, String email) {
        User u = User.createNew(name, email);
        u.persist();
    }

    @Transactional
    User readUser(String id) {
        return User.findById(id);
    }

    private void stubTokenExchange(String tokenUrl, String idToken, String accessToken) {
        try {
            String body = new ObjectMapper().writeValueAsString(
                    Map.of("id_token", idToken, "access_token", accessToken, "token_type", "Bearer"));
            http.postFormStub(tokenUrl, 200, body, null);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
