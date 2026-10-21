package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * settings-edge-hubs-toggle: most installations have exactly one hub, and for
 * them every mention of an edge is noise — a menu entry that leads nowhere and
 * a selector with one option.
 *
 * <p>The switch exists rather than deriving the answer from "are there any
 * edge hubs?" because of the order things happen in: with the menu hidden
 * while none exist, there would be no way to reach the page that creates the
 * first one.
 *
 * <p>Turning it off again is refused while edge hubs still exist, for the same
 * reason the peer assignment is immutable — hiding something that is still
 * doing work makes the install harder to reason about, not simpler.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EdgeHubsToggleTest {

    private static final String MARK = "edgetoggle";

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        EdgeHub.delete("name like ?1", MARK + "-%");
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null) s.edgeHubsEnabled = false;
    }

    private void setEnabled(boolean on, int expectedStatus) {
        given().contentType("application/json")
                .body("{\"edgeHubsEnabled\":" + on + "}")
                .when().put("/api/v1/settings/edge-hubs")
                .then().statusCode(expectedStatus);
    }

    private String createEdge() {
        return given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-" + UUID.randomUUID() + "\",\"enabled\":true,"
                        + "\"lat\":-33.45,\"lng\":-70.67}")
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void edgeHubsAreOffUntilAnOperatorAsksForThem() {
        given().when().get("/api/v1/settings")
                .then().statusCode(200)
                .body("edgeHubsEnabled", is(false));
    }

    @Test
    void anEdgeHubCannotBeCreatedWhileTheFeatureIsOff() {
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-nope\",\"enabled\":true,\"lat\":0,\"lng\":0}")
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(409)
                .body(containsString("edge hubs are switched off"));
    }

    @Test
    void switchingItOnAllowsCreatingOne() {
        setEnabled(true, 200);
        createEdge();
        given().when().get("/api/v1/settings").then().body("edgeHubsEnabled", is(true));
    }

    @Test
    void itCannotBeSwitchedOffWhileEdgeHubsExist() {
        setEnabled(true, 200);
        createEdge();
        given().contentType("application/json").body("{\"edgeHubsEnabled\":false}")
                .when().put("/api/v1/settings/edge-hubs")
                .then().statusCode(409)
                .body(containsString("still configured"));
    }

    @Test
    void itCanBeSwitchedOffOnceTheLastEdgeHubIsGone() {
        setEnabled(true, 200);
        String id = createEdge();
        given().when().delete("/api/v1/edge-hubs/" + id).then().statusCode(204);
        setEnabled(false, 200);
        given().when().get("/api/v1/settings").then().body("edgeHubsEnabled", is(false));
    }
}
