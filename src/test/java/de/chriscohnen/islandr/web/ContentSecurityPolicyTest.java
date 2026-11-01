package de.chriscohnen.islandr.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/** csp-report-only: the policy carries the hashes of the inline scripts actually served. */
@QuarkusTest
class ContentSecurityPolicyTest {

    @BeforeEach
    void resetThrottle() {
        CspReportResource.resetThrottleForTest();
    }

    private static String sha(String script) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
        return "'sha256-" + Base64.getEncoder().encodeToString(d) + "'";
    }

    @Test
    void policyCarriesTheHashOfEveryInlineScriptInIndexHtml() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/META-INF/resources/index.html"));
        String theme = html.substring(html.indexOf("<script>") + 8, html.indexOf("</script>"));
        int imap = html.indexOf("<script type=\"importmap\">") + "<script type=\"importmap\">".length();
        String importmap = html.substring(imap, html.indexOf("</script>", imap));

        String header = given().when().get("/index.html").then().statusCode(200)
                .extract().header("Content-Security-Policy-Report-Only");

        assertThat(header).contains(sha(theme)).contains(sha(importmap))
                .contains("frame-ancestors 'none'").contains("report-uri /api/v1/csp-report")
                .doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    void apiAnswersCarryTheHeaderToo() {
        given().when().get("/api/v1/auth/me").then()
                .header("Content-Security-Policy-Report-Only", containsString("default-src 'self'"));
    }

    @Test
    void reportIsAcceptedWithoutLogin_oversizedBodyToo() {
        given().contentType("application/csp-report").body("{\"csp-report\":{\"blocked-uri\":\"inline\"}}".getBytes())
                .when().post("/api/v1/csp-report").then().statusCode(204);
        given().contentType("application/csp-report").body("x".repeat(100_000).getBytes())
                .when().post("/api/v1/csp-report").then().statusCode(204);
    }

    @Test
    void floodIsThrottled() {
        long now = System.currentTimeMillis();
        int allowed = 0;
        for (int i = 0; i < 100; i++) if (CspReportResource.allow(now + 61_000)) allowed++;
        assertThat(allowed).isEqualTo(CspReportResource.MAX_LOGS_PER_MINUTE);
    }
}
