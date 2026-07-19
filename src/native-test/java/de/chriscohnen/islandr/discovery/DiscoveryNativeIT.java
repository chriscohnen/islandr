package de.chriscohnen.islandr.discovery;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a full discovery scan/import cycle against the actual packaged native
 * binary — not the JVM test suite, which cannot see native-image serialization
 * defects. Two such defects shipped in 0.12.0 (rc.3–rc.6) and were only caught
 * once the artifact was booted for real (see NativeReflectionConfig and
 * DiscoveryResource#startScan):
 *
 * <ol>
 *   <li>the discovery DTOs (ScanStatus, HostView, ScanStarted…) were missing
 *       from NativeReflectionConfig, so serializing a scan response threw;</li>
 *   <li>{@code POST /scan} returned its body via {@code Response.accepted(dto)},
 *       which is opaque to native's build-time serialization analysis — the
 *       response body came back empty (no jobId).</li>
 * </ol>
 *
 * <p>All 27 JVM {@code @QuarkusTest} discovery cases were green throughout both
 * incidents; only booting the native binary reproduces them. Stays in mock mode
 * (the default) so it needs no real network. See issue #25.
 */
@QuarkusIntegrationTest
class DiscoveryNativeIT {

    private static final String ADMIN_USER = "admin";
    // Must match the ISLANDR_ADMIN_PASSWORD the native binary is launched with —
    // wired in build.gradle.kts on the testNative task.
    private static final String ADMIN_PASSWORD = "native-it-pw";

    private String login() {
        var resp = given().contentType("application/json")
                .body("{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}")
                .when().post("/api/v1/auth/login");
        assertThat(resp.statusCode())
                .as("admin login must succeed — check ISLANDR_ADMIN_PASSWORD on the testNative task")
                .isEqualTo(200);
        return resp.getDetailedCookie("islandr_session").getValue();
    }

    private String createSite(String cookie, String name, String cidr) {
        return given().cookie("islandr_session", cookie).contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void scanAndImport_roundTripsThroughTheNativeBinary() {
        String cookie = login();
        String siteId = createSite(cookie, "native-it-disco", "10.222.0.0/29");

        // --- POST /scan → 202 + non-empty jobId ---------------------------------
        // Guards ScanStarted: a Response-wrapped entity broke native's build-time
        // serialization analysis and the field came back missing (rc.3–rc.6).
        var scanResp = given().cookie("islandr_session", cookie).contentType("application/json")
                .when().post("/api/v1/sites/" + siteId + "/discovery/scan")
                .then().statusCode(202)
                .extract().response();
        String jobId = scanResp.path("jobId");
        assertThat(jobId).as("ScanStarted.jobId must serialize in the native image").isNotBlank();

        // --- Poll /scan/{jobId} until done --------------------------------------
        // Guards ScanStatus + HostView: both were missing from native reflection
        // config, so serializing this response threw in the native image.
        JsonPath status = pollUntilDone(cookie, siteId, jobId);
        assertThat(status.getString("state")).isEqualTo("done");

        List<Map<String, Object>> hosts = status.getList("hosts");
        assertThat(hosts).as("mock mode always finds the two synthetic hosts").isNotEmpty();
        for (Map<String, Object> host : hosts) {
            assertThat(host.get("ip")).as("HostView.ip").isNotNull();
            assertThat(host.get("openPorts")).as("HostView.openPorts").isNotNull();
            assertThat(host.get("typeGuess")).as("HostView.typeGuess").isNotNull();
        }

        // --- POST /import → ImportResult ----------------------------------------
        String importBody = "{\"hosts\":[" + hosts.stream()
                .map(h -> "{\"ip\":\"" + h.get("ip") + "\",\"name\":\"native-it-host\","
                        + "\"type\":\"" + h.get("typeGuess") + "\"}")
                .reduce((a, b) -> a + "," + b).orElse("") + "]}";

        given().cookie("islandr_session", cookie).contentType("application/json").body(importBody)
                .when().post("/api/v1/sites/" + siteId + "/discovery/import")
                .then().statusCode(200)
                .body("imported", org.hamcrest.Matchers.equalTo(hosts.size()))
                .body("skipped", org.hamcrest.Matchers.equalTo(0));
    }

    private JsonPath pollUntilDone(String cookie, String siteId, String jobId) {
        // Mock-mode scans complete near-instantly (no real network I/O), but the
        // job still runs on a separate executor thread — poll instead of assuming
        // it's done by the time this method is called.
        for (int i = 0; i < 30; i++) {
            var resp = given().cookie("islandr_session", cookie)
                    .when().get("/api/v1/sites/" + siteId + "/discovery/scan/" + jobId)
                    .then().statusCode(200)
                    .extract().response();
            String state = resp.path("state");
            if (!"running".equals(state)) return resp.jsonPath();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("scan did not finish within the poll budget");
    }
}
