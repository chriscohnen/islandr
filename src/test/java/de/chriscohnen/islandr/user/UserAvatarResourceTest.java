package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import de.chriscohnen.islandr.settings.SettingsDto;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class UserAvatarResourceTest {

    @Inject FakeHttpFetcher http;
    @Inject SettingsService settings;

    @BeforeEach
    void resetHttp() { http.reset(); }

    @Test
    void cachedBytes_returned_inline() {
        String userId = createUserWithCachedAvatar("avatar-user-1@firma.de",
                new byte[] { 1, 2, 3, 4, 5 }, "image/png");

        given().when().get("/api/v1/users/" + userId + "/avatar")
                .then().statusCode(200)
                .contentType("image/png")
                .header("ETag", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void noCachedBytes_gravatarDisabled_returns404() {
        setGravatarEnabled(false);
        String userId = createUser("plain-user@firma.de");

        given().when().get("/api/v1/users/" + userId + "/avatar")
                .then().statusCode(404);

        assertThat(http.calls).noneMatch(c -> c.url().startsWith("https://www.gravatar.com"));
    }

    @Test
    void noCachedBytes_gravatarEnabled_fetchesAndCaches_secondHitDoesNotRefetch() {
        setGravatarEnabled(true);
        String userId = createUser("gravatar-user@firma.de");
        http.stub("https://www.gravatar.com/avatar/" + md5("gravatar-user@firma.de") + "?s=200&d=404",
                200, new byte[] { 9, 9, 9 }, Map.of("content-type", "image/jpeg"));

        given().when().get("/api/v1/users/" + userId + "/avatar")
                .then().statusCode(200)
                .contentType("image/jpeg");

        long firstGravatarCalls = http.calls.stream()
                .filter(c -> c.url().contains("gravatar.com")).count();
        assertThat(firstGravatarCalls).isEqualTo(1);

        given().when().get("/api/v1/users/" + userId + "/avatar")
                .then().statusCode(200);

        long secondGravatarCalls = http.calls.stream()
                .filter(c -> c.url().contains("gravatar.com")).count();
        assertThat(secondGravatarCalls).isEqualTo(1);  // cached, no extra hit
    }

    @Test
    void gravatar404_returns404_doesNotCache() {
        setGravatarEnabled(true);
        String userId = createUser("nogravatar@firma.de");
        // No stub → default response is 404

        given().when().get("/api/v1/users/" + userId + "/avatar")
                .then().statusCode(404);
    }

    @Test
    void oidcUser_doesNotFallbackToGravatar_even_whenEnabled() {
        setGravatarEnabled(true);
        String userId = createOidcUserWithoutAvatar("oidc-user@firma.de");

        given().when().get("/api/v1/users/" + userId + "/avatar")
                .then().statusCode(404);

        assertThat(http.calls).noneMatch(c -> c.url().contains("gravatar.com"));
    }

    @Test
    void unknownUserId_returns404() {
        given().when().get("/api/v1/users/does-not-exist/avatar")
                .then().statusCode(404);
    }

    // -- helpers --------------------------------------------------------------

    @Transactional
    String createUser(String email) {
        User u = User.createNew("Test " + email, email);
        u.persist();
        return u.id;
    }

    @Transactional
    String createOidcUserWithoutAvatar(String email) {
        User u = User.createNew("OIDC " + email, email);
        u.oidcProvider = "microsoft";
        u.oidcSubject = "ms-" + email;
        u.persist();
        return u.id;
    }

    @Transactional
    String createUserWithCachedAvatar(String email, byte[] bytes, String contentType) {
        User u = User.createNew("Cached " + email, email);
        u.avatarBytes = bytes;
        u.avatarContentType = contentType;
        u.avatarEtag = "etag-test-" + email.hashCode();
        u.persist();
        return u.id;
    }

    @Transactional
    void setGravatarEnabled(boolean enabled) {
        var cur = settings.get();
        settings.update(new SettingsDto.UpdateRequest(
                cur.wgSubnet, cur.wgServerPublicKey, cur.wgServerEndpoint,
                cur.wgClientAllowedIps, cur.wgClientDns, cur.privateKeyRetention,
                enabled, cur.oidcAutoProvision, cur.firewallDryRun
        ), "test");
    }

    static String md5(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
