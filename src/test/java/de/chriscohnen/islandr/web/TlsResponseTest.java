package de.chriscohnen.islandr.web;

import de.chriscohnen.islandr.auth.SessionFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over a real TLS connection (the placeholder certificate): the session cookie
 * is Secure, and HSTS is withheld because a browser could not be held to a
 * certificate nobody can validate.
 */
@QuarkusTest
class TlsResponseTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "8444")
    int sslPort;

    @Inject SecurityHeaders securityHeaders;

    /** Whether HSTS is sent depends on the certificate other tests in the same run may have
     *  installed (a managed one is trustworthy, the placeholder is not), so the test asserts
     *  the rule, not a fixed outcome: HSTS exactly when the served certificate is a trusted one. */
    @Test
    void loginOverTls_setsASecureCookie_andHstsOnlyForATrustedCertificate() {
        Response r = given().relaxedHTTPSValidation().baseUri("https://localhost").port(sslPort)
                .contentType("application/json")
                .body("{ \"username\": \"admin\", \"password\": \"test-admin-pw\" }")
                .when().post("/api/v1/auth/login");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.getDetailedCookie(SessionFilter.COOKIE_NAME).isSecured()).isTrue();
        if (securityHeaders.directTlsTrusted()) {
            assertThat(r.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
        } else {
            assertThat(r.getHeader("Strict-Transport-Security")).isNull();
        }
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
}
