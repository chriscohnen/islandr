package de.chriscohnen.islandr.acl;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * HTTP surface of the reservation feature (#72). The status codes carry
 * meaning the UI depends on: 409 (not 403) for "taken right now", with the
 * holders in the body so the portal can name them.
 */
@QuarkusTest
class ReservationResourceTest {

    private String adminCookie() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "test-admin-pw"))
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().cookie("islandr_session");
    }

    private String siteId(String cookie) {
        return given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "RSVAPI-" + UUID.randomUUID().toString().substring(0, 8),
                        "cidr", "10.96.0.0/24"))
                .when().post("/api/v1/sites")
                .then().statusCode(201)
                .extract().path("id");
    }

    private String resourceId(String cookie, String siteId) {
        return given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "RSVAPI-Res-" + UUID.randomUUID().toString().substring(0, 8),
                        "ip", "10.96.0." + (2 + (int) (Math.random() * 200)),
                        "type", "computer"))
                .when().post("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(201)
                .extract().path("id");
    }

    /** A port carrying a capacity limit — the unit reservations are counted in. */
    private String capacityPort(String cookie, String resourceId, int capacity) {
        return given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("port", 3389, "transport", "tcp", "protocol", "RDP",
                        "label", "RDP", "rdpClipboard", false, "rdpFileTransfer", false,
                        "maxConcurrentUsers", capacity,
                        "autoApproveReservations", true))
                .when().post("/api/v1/resources/" + resourceId + "/ports")
                .then().statusCode(200)
                .extract().path("id");
    }

    @Test
    void portCreate_acceptsAndReturnsTheCapacityConfig() {
        String cookie = adminCookie();
        String res = resourceId(cookie, siteId(cookie));
        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("port", 3389, "transport", "tcp", "protocol", "RDP",
                        "rdpClipboard", false, "rdpFileTransfer", false,
                        "maxConcurrentUsers", 1,
                        "maxReservationMinutes", 240,
                        "autoApproveReservations", false))
                .when().post("/api/v1/resources/" + res + "/ports")
                .then().statusCode(200)
                .body("maxConcurrentUsers", is(1))
                .body("maxReservationMinutes", is(240))
                .body("autoApproveReservations", is(false));

        given().cookie("islandr_session", cookie)
                .when().get("/api/v1/resources/" + res)
                .then().statusCode(200)
                .body("ports[0].maxConcurrentUsers", is(1));
    }

    @Test
    void portCreate_withoutCapacityFields_staysUnlimited_backwardCompatible() {
        String cookie = adminCookie();
        String res = resourceId(cookie, siteId(cookie));
        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("port", 22, "transport", "tcp", "protocol", "SSH",
                        "rdpClipboard", false, "rdpFileTransfer", false))
                .when().post("/api/v1/resources/" + res + "/ports")
                .then().statusCode(200)
                .body("maxConcurrentUsers", org.hamcrest.Matchers.nullValue())
                .body("autoApproveReservations", is(true));
    }

    @Test
    void listReservations_requiresAdmin() {
        given().when().get("/api/v1/reservations").then().statusCode(401);
    }

    @Test
    void listReservations_asAdmin_returnsArray() {
        String cookie = adminCookie();
        given().cookie("islandr_session", cookie)
                .when().get("/api/v1/reservations")
                .then().statusCode(200)
                .body("$", notNullValue());
    }

    /**
     * Being an admin is not a grant: reserving a resource nobody has granted
     * you is 403, before any capacity information is returned. Keeps the
     * endpoint from doubling as a way to enumerate who is using what.
     */
    @Test
    void request_withoutAGrantOnTheResource_isForbidden() {
        String cookie = adminCookie();
        String res = resourceId(cookie, siteId(cookie));
        String port = capacityPort(cookie, res, 1);

        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("portId", port, "minutes", 60))
                .when().post("/api/v1/reservations")
                .then().statusCode(403);
    }

    @Test
    void request_onAPortWithoutACapacityLimit_is400NotAReservation() {
        String cookie = adminCookie();
        String res = resourceId(cookie, siteId(cookie));
        String openPort = given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("port", 22, "transport", "tcp", "protocol", "SSH",
                        "rdpClipboard", false, "rdpFileTransfer", false))
                .when().post("/api/v1/resources/" + res + "/ports")
                .then().statusCode(200).extract().path("id");

        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("portId", openPort, "minutes", 60))
                .when().post("/api/v1/reservations")
                .then().statusCode(400);
    }

    @Test
    void mine_returnsAnArrayForAnAuthenticatedCaller() {
        String cookie = adminCookie();
        given().cookie("islandr_session", cookie)
                .when().get("/api/v1/reservations/mine")
                .then().statusCode(200)
                .body("$", notNullValue());
    }
}
