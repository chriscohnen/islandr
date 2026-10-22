package de.chriscohnen.islandr.dashboard;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.TrafficWindowStats;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * traffic-window-tile: the dashboard says how many bytes actually moved in the
 * last hour.
 *
 * <p>Unlike the DNS tile beside it, this one is always present. Every
 * installation moves bytes, so an hour with none is an answer — "nothing
 * happened" — and hiding the tile would make that indistinguishable from a
 * feature that isn't there. The window length travels with the numbers for the
 * same reason: after a restart the count starts from zero, and a tile that
 * names its own window explains that instead of puzzling.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DashboardTrafficTileTest {

    @Inject TrafficWindowStats traffic;

    @Test
    void theTileIsPresentEvenWithNoTrafficAtAll() {
        given().when().get("/api/v1/dashboard")
                .then().statusCode(200)
                .body("traffic", notNullValue())
                .body("traffic.windowMinutes", is(60))
                .body("traffic.rxBytes", greaterThanOrEqualTo(0))
                .body("traffic.txBytes", greaterThanOrEqualTo(0));
    }

    @Test
    void recordedBytesReachTheDashboard() {
        traffic.record(4096, 2048);
        given().when().get("/api/v1/dashboard")
                .then().statusCode(200)
                .body("traffic.rxBytes", greaterThanOrEqualTo(4096))
                .body("traffic.txBytes", greaterThanOrEqualTo(2048));
    }
}
