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

    private String capacityResource(String cookie, String siteId, int capacity) {
        return given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "RSVAPI-Res-" + UUID.randomUUID().toString().substring(0, 8),
                        "ip", "10.96.0." + (2 + (int) (Math.random() * 200)),
                        "type", "computer",
                        "maxConcurrentUsers", capacity,
                        "autoApproveReservations", true))
                .when().post("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void resourceCreate_acceptsAndReturnsTheCapacityConfig() {
        String cookie = adminCookie();
        String site = siteId(cookie);
        String id = given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "RSVAPI-Cap-" + UUID.randomUUID().toString().substring(0, 8),
                        "ip", "10.96.0.240", "type", "computer",
                        "maxConcurrentUsers", 1,
                        "maxReservationMinutes", 240,
                        "autoApproveReservations", false))
                .when().post("/api/v1/sites/" + site + "/resources")
                .then().statusCode(201)
                .body("maxConcurrentUsers", is(1))
                .body("maxReservationMinutes", is(240))
                .body("autoApproveReservations", is(false))
                .extract().path("id");

        given().cookie("islandr_session", cookie)
                .when().get("/api/v1/resources/" + id)
                .then().statusCode(200)
                .body("maxConcurrentUsers", is(1));
    }

    @Test
    void resourceCreate_withoutCapacityFields_staysUnlimited_backwardCompatible() {
        String cookie = adminCookie();
        String site = siteId(cookie);
        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "RSVAPI-Unl-" + UUID.randomUUID().toString().substring(0, 8),
                        "ip", "10.96.0.241", "type", "computer"))
                .when().post("/api/v1/sites/" + site + "/resources")
                .then().statusCode(201)
                .body("maxConcurrentUsers", is(nullValue()));
    }

    private static org.hamcrest.Matcher<Object> nullValue() {
        return org.hamcrest.Matchers.nullValue();
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
        String site = siteId(cookie);
        String res = capacityResource(cookie, site, 1);

        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("resourceId", res, "minutes", 60))
                .when().post("/api/v1/reservations")
                .then().statusCode(403);
    }

    @Test
    void request_onAResourceWithoutACapacityLimit_is400NotAReservation() {
        String cookie = adminCookie();
        String site = siteId(cookie);
        String unlimited = given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("name", "RSVAPI-NoCap-" + UUID.randomUUID().toString().substring(0, 8),
                        "ip", "10.96.0.242", "type", "computer"))
                .when().post("/api/v1/sites/" + site + "/resources")
                .then().statusCode(201).extract().path("id");

        given().cookie("islandr_session", cookie).contentType(ContentType.JSON)
                .body(Map.of("resourceId", unlimited, "minutes", 60))
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
