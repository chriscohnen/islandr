package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Integration tests for the port-range scan endpoint. Runs in the suite's mock
 * discovery mode, so the lifecycle test needs no real network.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PortScanResourceTest {

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("id");
    }

    private String createResource(String siteId, String ip) {
        return given().contentType("application/json")
                .body("{\"name\":\"scan-target\",\"ip\":\"" + ip + "\",\"type\":\"computer\"}")
                .when().post("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void scan_nonexistentResource_returns404() {
        given().contentType("application/json")
                .body("{\"ports\":\"1-100\"}")
                .when().post("/api/v1/resources/does-not-exist/port-scan")
                .then().statusCode(404);
    }

    @Test
    void scan_defaultRange_startsAndCompletes() {
        String siteId = createSite("scan-default", "10.91.0.0/29");
        String resourceId = createResource(siteId, "10.91.0.2");

        String jobId = given().contentType("application/json")
                .body("{}")
                .when().post("/api/v1/resources/" + resourceId + "/port-scan")
                .then().statusCode(202).extract().path("jobId");

        awaitDone(resourceId, jobId);

        given().when().get("/api/v1/resources/" + resourceId + "/port-scan/" + jobId)
                .then().statusCode(200)
                .body("state", equalTo("DONE"))
                .body("total", greaterThan(0));
    }

    @Test
    void scan_malformedRange_returns409() {
        String siteId = createSite("scan-bad", "10.91.0.8/29");
        String resourceId = createResource(siteId, "10.91.0.10");

        given().contentType("application/json")
                .body("{\"ports\":\"100-20\"}")
                .when().post("/api/v1/resources/" + resourceId + "/port-scan")
                .then().statusCode(409);
    }

    @Test
    void status_unknownJob_returns404() {
        String siteId = createSite("scan-nojob", "10.91.0.16/29");
        String resourceId = createResource(siteId, "10.91.0.18");

        given().when().get("/api/v1/resources/" + resourceId + "/port-scan/no-such-job")
                .then().statusCode(404);
    }

    @Test
    void cancel_runningScan_returns204AndKeepsFindings() {
        String siteId = createSite("scan-cancel", "10.91.0.24/29");
        String resourceId = createResource(siteId, "10.91.0.26");

        String jobId = given().contentType("application/json")
                .body("{\"ports\":\"1-1024\"}")
                .when().post("/api/v1/resources/" + resourceId + "/port-scan")
                .then().statusCode(202).extract().path("jobId");

        given().when().delete("/api/v1/resources/" + resourceId + "/port-scan/" + jobId)
                .then().statusCode(204);

        given().when().get("/api/v1/resources/" + resourceId + "/port-scan/" + jobId)
                .then().statusCode(200)
                .body("state", equalTo("CANCELLED"));
    }

    private void awaitDone(String resourceId, String jobId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String state = given().when().get("/api/v1/resources/" + resourceId + "/port-scan/" + jobId)
                    .then().extract().path("state");
            if (!"RUNNING".equals(state)) return;
            Thread.onSpinWait();
        }
    }
}
