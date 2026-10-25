package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * CRUD for /api/v1/edge-hubs (edge-hub-entity, loop/TASK.md, 1.2.0), plus
 * the Site-assignment side (sites.edgeHubId) and the delete-falls-back
 * behavior EdgeHubService.delete documents.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class EdgeHubResourceTest {

    @BeforeEach
    @Transactional
    void wipe() {
        Site.deleteAll();
        EdgeHub.deleteAll();
        // settings-edge-hubs-toggle: off by default, and creating an edge hub
        // is refused while it is. Every test here is about the edge-hub API
        // itself, so the switch is a precondition, not the subject —
        // EdgeHubsToggleTest covers the switch.
        de.chriscohnen.islandr.settings.Settings s =
                de.chriscohnen.islandr.settings.Settings.findById(
                        de.chriscohnen.islandr.settings.Settings.SINGLETON_ID);
        if (s != null) s.edgeHubsEnabled = true;
    }

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    @Test
    void create_persistsAndReturnsIt() {
        String name = "Munich-" + uniq();
        given().contentType(ContentType.JSON)
                .body(Map.of("name", name, "enabled", true, "lat", 48.1351, "lng", 11.5820))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201)
                .body("name", equalTo(name))
                .body("enabled", equalTo(true))
                .body("siteCount", equalTo(0));

        given().when().get("/api/v1/edge-hubs")
                .then().statusCode(200)
                .body("name", org.hamcrest.Matchers.hasItem(name));
    }

    @Test
    void create_rejectsADuplicateName() {
        String name = "Hamburg-" + uniq();
        given().contentType(ContentType.JSON)
                .body(Map.of("name", name, "enabled", true, "lat", 53.5511, "lng", 9.9937))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body(Map.of("name", name, "enabled", true, "lat", 1.0, "lng", 1.0))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(409);
    }

    @Test
    void create_rejectsOutOfRangeCoordinates() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "Bad-" + uniq(), "enabled", true, "lat", 200.0, "lng", 11.0))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(400);
    }

    @Test
    void update_changesFields() {
        String id = given().contentType(ContentType.JSON)
                .body(Map.of("name", "Edit-" + uniq(), "enabled", true, "lat", 1.0, "lng", 1.0))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");

        String renamed = "Renamed-" + uniq();
        given().contentType(ContentType.JSON)
                .body(Map.of("name", renamed, "enabled", false, "lat", 2.0, "lng", 2.0))
                .when().put("/api/v1/edge-hubs/" + id)
                .then().statusCode(200)
                .body("name", equalTo(renamed))
                .body("enabled", equalTo(false))
                .body("lat", equalTo(2.0f))
                .body("lng", equalTo(2.0f));
    }

    @Test
    void siteAssignment_showsUpInSiteResponse_andFallsBackOnDelete() {
        String hubId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "Köln-" + uniq(), "enabled", true, "lat", 50.9375, "lng", 6.9603))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");

        String siteName = "Außenstelle-" + uniq();
        given().contentType(ContentType.JSON)
                .body(Map.of("name", siteName, "cidr", "10.90.0.0/24", "edgeHubId", hubId))
                .when().post("/api/v1/sites")
                .then().statusCode(201)
                .body("edgeHubId", equalTo(hubId));

        given().when().get("/api/v1/edge-hubs/" + hubId)
                .then().statusCode(200).body("siteCount", equalTo(1));

        // Deleting the edge hub falls the site back to the central hub
        // (edgeHubId null), not a cascading delete — EdgeHubService.delete.
        given().when().delete("/api/v1/edge-hubs/" + hubId).then().statusCode(204);

        given().when().get("/api/v1/sites")
                .then().statusCode(200)
                .body("find { it.name == '" + siteName + "' }.edgeHubId", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void create_rejectsAnUnknownEdgeHubIdOnASite() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "Orphan-" + uniq(), "cidr", "10.91.0.0/24", "edgeHubId", "does-not-exist"))
                .when().post("/api/v1/sites")
                .then().statusCode(404);
    }

    @Test
    void delete_removesIt() {
        String id = given().contentType(ContentType.JSON)
                .body(Map.of("name", "Delete-" + uniq(), "enabled", true, "lat", 1.0, "lng", 1.0))
                .when().post("/api/v1/edge-hubs")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/v1/edge-hubs/" + id).then().statusCode(204);
        given().when().get("/api/v1/edge-hubs/" + id).then().statusCode(404);
    }
}
